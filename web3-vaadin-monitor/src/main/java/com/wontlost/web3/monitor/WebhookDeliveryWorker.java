package com.wontlost.web3.monitor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.wontlost.web3.monitor.service.db.Delivery;
import com.wontlost.web3.monitor.service.db.DeliveryRepository;
import com.wontlost.web3.monitor.service.security.WebhookUrlPolicy;

@Component
public class WebhookDeliveryWorker implements DisposableBean {
    private static final List<Duration> RETRIES=List.of(Duration.ofMinutes(1),Duration.ofMinutes(5),Duration.ofMinutes(15),Duration.ofHours(1),Duration.ofHours(6),Duration.ofHours(24));
    private final DeliveryRepository deliveries;
    private final WebhookUrlPolicy urlPolicy;
    private final CloseableHttpClient http;
    private final Clock clock;
    public WebhookDeliveryWorker(DeliveryRepository deliveries,WebhookUrlPolicy urlPolicy,Clock clock){
        this.deliveries=deliveries;this.urlPolicy=urlPolicy;this.clock=clock;
        this.http=createClient(urlPolicy);
    }

    /** 连接时的 DNS 解析走 SSRF 策略：校验过的地址就是实际连接的地址，杜绝"校验后重新解析到内网"的 DNS 重绑定；TLS 仍按主机名校验。 */
    static CloseableHttpClient createClient(WebhookUrlPolicy urlPolicy){
        SystemDefaultDnsResolver resolver=new SystemDefaultDnsResolver(){
            @Override public InetAddress[] resolve(String host) throws UnknownHostException { return urlPolicy.resolveAllowed(host); }
        };
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver)
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(10))
                                .setSocketTimeout(Timeout.ofSeconds(10)).build()).build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(10)).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build();
    }
    @Scheduled(fixedDelayString="${monitor.poll-interval:5s}")
    public void deliverDue(){
        Instant now=clock.instant();
        for(Delivery candidate:deliveries.due(now,50)){
            String leaseToken=deliveries.lease(candidate.id(),now,now.plusSeconds(60));
            if(leaseToken==null)continue;
            Delivery delivery=new Delivery(candidate.id(),candidate.merchantId(),candidate.intentId(),candidate.eventType(),candidate.payload(),candidate.status(),candidate.attempts()+1,candidate.nextAttemptAt(),candidate.lastError(),candidate.webhookUrl(),candidate.webhookSecret());
            deliver(delivery,leaseToken,now);
        }
    }
    private void deliver(Delivery delivery,String leaseToken,Instant now){
        try{
            urlPolicy.validate(delivery.webhookUrl());
            long timestamp=now.getEpochSecond();
            String signature=WebhookSignatures.sign(delivery.webhookSecret(),timestamp,delivery.payload());
            HttpPost request=new HttpPost(delivery.webhookUrl());
            request.setHeader("X-Web3-Monitor-Event-Id",delivery.id());
            request.setHeader("X-Web3-Monitor-Signature","t="+timestamp+",v1="+signature);
            request.setEntity(new StringEntity(delivery.payload(),ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));
            int status=http.execute(request,response->response.getCode());
            if(status>=200&&status<300){deliveries.delivered(delivery.id(),leaseToken,clock.instant());return;}
            fail(delivery,leaseToken,now,"Webhook returned HTTP "+status);
        }catch(Exception exception){fail(delivery,leaseToken,now,exception.getMessage()==null?exception.getClass().getSimpleName():exception.getMessage());}
    }
    private void fail(Delivery delivery,String leaseToken,Instant now,String error){
        int attempts=delivery.attempts();
        Instant next=attempts>=7?now:now.plus(RETRIES.get(Math.min(attempts-1,RETRIES.size()-1)));
        deliveries.retry(delivery.id(),leaseToken,attempts,now,error,next);
    }
    @Override public void destroy() throws IOException { http.close(); }
}
