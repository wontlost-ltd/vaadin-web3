package com.wontlost.web3.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.wontlost.web3.x402.http.X402PaymentFilter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "web3.x402.demo.deploy-on-startup=false"
})
class X402HttpFilterDemoTest {
    @LocalServerPort
    private int port;

    @Autowired
    private X402PaymentFilter filter;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void anonymousQuoteRequestGetsPaymentChallengeInsteadOfLoginRedirect() throws Exception {
        assertNotNull(filter);
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/x402/quote"))
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(402, response.statusCode());
        assertNotNull(response.headers().firstValue(X402PaymentFilter.PAYMENT_REQUIRED).orElse(null));
    }

    @Test
    void demoDoesNotExposeASeparatePrepareEndpoint() throws Exception {
        assertFalse(mappings.getHandlerMethods().keySet().stream()
                .anyMatch(mapping -> mapping.getPatternValues().contains("/api/x402/prepare")));
    }
}
