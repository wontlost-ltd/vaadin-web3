package com.wontlost.web3.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.wontlost.web3.monitor.service.MonitorProperties;
import com.wontlost.web3.monitor.service.security.WebhookUrlPolicy;

/** 投递客户端必须用策略校验过的同一份 DNS 结果建立连接（防 DNS 重绑定）。 */
class WebhookDnsPinningTest {
    private HttpServer receiver;
    private final AtomicInteger received = new AtomicInteger();

    @BeforeEach void start() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> { received.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        receiver.start();
    }

    @AfterEach void stop() { receiver.stop(0); }

    @Test void connectionUsesThePolicyResolver() throws Exception {
        // webhook.test 在系统 DNS 中不存在：能投递成功，说明连接确实走了策略的解析器
        WebhookUrlPolicy policy = new WebhookUrlPolicy(properties(true), host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") });
        try (CloseableHttpClient http = WebhookDeliveryWorker.createClient(policy)) {
            int status = http.execute(new HttpPost(url("webhook.test")), response -> response.getCode());
            assertEquals(204, status);
        }
        assertEquals(1, received.get());
    }

    @Test void rebindingToAnInternalAddressAfterValidationIsBlocked() throws Exception {
        // 第一次解析（注册/校验时）返回公网地址，之后（连接时）返回本机地址——典型的 DNS 重绑定
        AtomicInteger lookups = new AtomicInteger();
        WebhookUrlPolicy policy = new WebhookUrlPolicy(properties(false), host -> new InetAddress[] {
                InetAddress.getByName(lookups.getAndIncrement() == 0 ? "93.184.216.34" : "127.0.0.1") });

        assertEquals(1, policy.validate(url("rebind.test")).size());
        try (CloseableHttpClient http = WebhookDeliveryWorker.createClient(policy)) {
            assertThrows(UnknownHostException.class, () -> http.execute(new HttpPost(url("rebind.test")), response -> response.getCode()));
        }
        assertEquals(0, received.get(), "no request may reach the internal address");
        assertTrue(lookups.get() >= 2, "the connection must perform its own policy-checked lookup");
    }

    @Test void reservedRangesAreRejected() throws Exception {
        WebhookUrlPolicy policy = new WebhookUrlPolicy(properties(false), host -> new InetAddress[] { InetAddress.getByName(host) });
        for (String internal : new String[] { "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "0.1.2.3", "100.64.0.1", "198.18.0.1", "192.0.0.8", "240.0.0.1", "255.255.255.255", "::1", "fc00::1",
                "fe80::1", "64:ff9b::a00:1", "::ffff:10.0.0.1" }) {
            assertThrows(UnknownHostException.class, () -> policy.resolveAllowed(internal), internal);
        }
        for (String external : new String[] { "93.184.216.34", "1.1.1.1", "2606:4700:4700::1111" }) {
            assertEquals(1, policy.resolveAllowed(external).length, external);
        }
        assertFalse(new WebhookUrlPolicy(properties(true), host -> new InetAddress[] { InetAddress.getByName(host) })
                .resolveAllowed("127.0.0.1").length == 0, "private targets are allowed when explicitly enabled");
    }

    private String url(String host) { return "http://" + host + ":" + receiver.getAddress().getPort() + "/hook"; }

    private static MonitorProperties properties(boolean allowPrivate) {
        MonitorProperties properties = new MonitorProperties();
        properties.getWebhooks().setAllowInsecure(true);
        properties.getWebhooks().setAllowPrivateTargets(allowPrivate);
        return properties;
    }
}
