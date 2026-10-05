package com.wontlost.web3.monitor;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;
import com.vaadin.flow.server.VaadinContext;

class PaymentMonitorClientTest {
    private static final String PAYMENT="""
            {"id":"00000000-0000-0000-0000-000000000001","orderId":"o-1","chainId":1,"token":{"symbol":"USDC","address":"0x0000000000000000000000000000000000000001","decimals":6},"recipient":"0x0000000000000000000000000000000000000002","amount":"1.25","payer":null,"minConfirmations":1,"status":"PENDING","txHash":"0xabc","paidAmount":"0","confirmations":0,"notBefore":"2026-01-01T00:00:00Z","expiresAt":"2026-01-01T01:00:00Z","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:01Z"}
            """;

    @Test void sendsAndParsesCreateSubmitAndGetRequests() {
        StubHttpClient http=new StubHttpClient(201,PAYMENT);
        PaymentMonitorClient client=new PaymentMonitorClient(URI.create("https://monitor.example/"),"wm_secret",http,Clock.systemUTC());
        var created=client.createPayment(new CreatePaymentRequest("o-1",1,"USDC","0x0000000000000000000000000000000000000002","1.25",null,1,null));
        assertEquals("00000000-0000-0000-0000-000000000001",created.id());assertEquals(MonitoredStatus.PENDING,created.status());assertEquals("USDC",created.token().symbol());
        assertEquals("Bearer wm_secret",http.request.headers().firstValue("Authorization").orElseThrow());
        assertEquals("POST",http.request.method());assertTrue(http.request.uri().toString().endsWith("/v1/payments"));
        http.status=200;client.submitTransaction("00000000-0000-0000-0000-000000000001","0xabc");assertTrue(http.request.uri().toString().endsWith("/v1/payments/00000000-0000-0000-0000-000000000001/transaction"));
        client.getPayment("00000000-0000-0000-0000-000000000001");assertEquals("GET",http.request.method());
    }

    @Test void preservesHttpErrorStatusAndBody() {
        StubHttpClient http=new StubHttpClient(401,"{\"error\":\"Invalid API key\"}");
        PaymentMonitorClient client=new PaymentMonitorClient(URI.create("https://monitor.example/"),"bad",http,Clock.systemUTC());
        PaymentMonitorException error=assertThrows(PaymentMonitorException.class,()->client.getPayment("00000000-0000-0000-0000-000000000001"));
        assertEquals(401,error.statusCode());assertTrue(error.errorBody().contains("Invalid API key"));
    }

    @Test void storesOneClientPerContextAndClientIsNotSerializable() {
        VaadinContext context=new MemoryVaadinContext();
        PaymentMonitorClient first=new PaymentMonitorClient(URI.create("https://monitor.example/"),"wm_one",new StubHttpClient(200,PAYMENT),Clock.systemUTC());
        PaymentMonitorClient.register(context,first);PaymentMonitorClient.register(context,first);
        assertSame(first,PaymentMonitorClient.find(context).orElseThrow());
        assertThrows(IllegalStateException.class,()->PaymentMonitorClient.register(context,new PaymentMonitorClient(URI.create("https://monitor.example/"),"wm_two",new StubHttpClient(200,PAYMENT),Clock.systemUTC())));
        assertFalse(java.io.Serializable.class.isAssignableFrom(PaymentMonitorClient.class));
    }

    private static final class StubHttpClient extends HttpClient {
        private int status;private final String body;private HttpRequest request;
        StubHttpClient(int status,String body){this.status=status;this.body=body;}
        @Override public <T> HttpResponse<T> send(HttpRequest request,HttpResponse.BodyHandler<T> handler){
            this.request=request;
            @SuppressWarnings("unchecked") T value=(T)body;
            return new Response<>(status,value,request);
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
        @Override public Optional<CookieHandler> cookieHandler(){return Optional.empty();}
        @Override public Optional<Duration> connectTimeout(){return Optional.empty();}
        @Override public Redirect followRedirects(){return Redirect.NEVER;}
        @Override public Optional<ProxySelector> proxy(){return Optional.empty();}
        @Override public SSLContext sslContext(){try{return SSLContext.getDefault();}catch(Exception e){throw new IllegalStateException(e);}}
        @Override public SSLParameters sslParameters(){return new SSLParameters();}
        @Override public Optional<Authenticator> authenticator(){return Optional.empty();}
        @Override public Version version(){return Version.HTTP_1_1;}
        @Override public Optional<Executor> executor(){return Optional.empty();}
    }
    private record Response<T>(int statusCode,T body,HttpRequest request) implements HttpResponse<T> {
        public Optional<HttpResponse<T>> previousResponse(){return Optional.empty();}
        public HttpHeaders headers(){return HttpHeaders.of(Map.of(),(a,b)->true);}
        public Optional<javax.net.ssl.SSLSession> sslSession(){return Optional.empty();}
        public URI uri(){return request.uri();}
        public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }
    private static final class MemoryVaadinContext implements VaadinContext {
        private final Map<Class<?>,Object> values=new HashMap<>();
        @Override public <T>T getAttribute(Class<T> type,java.util.function.Supplier<T> supplier){return type.cast(values.computeIfAbsent(type,k->supplier.get()));}
        @Override public <T>void setAttribute(Class<T> type,T value){values.put(type,value);}
        @Override public void removeAttribute(Class<?> type){values.remove(type);}
        @Override public Enumeration<String> getContextParameterNames(){return java.util.Collections.emptyEnumeration();}
        @Override public String getContextParameter(String name){return null;}
    }
}
