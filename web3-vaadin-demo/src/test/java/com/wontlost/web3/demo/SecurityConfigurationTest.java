package com.wontlost.web3.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigurationTest {
    @LocalServerPort
    private int port;

    @Test
    void unauthenticatedAccountRouteRedirectsToLogin() throws Exception {
        HttpResponse<String> response = get("/account");
        assertEquals(302, response.statusCode());
        org.junit.jupiter.api.Assertions.assertTrue(response.headers().firstValue("location").orElse("").contains("login"));
    }

    @Test
    void loginRouteIsPublic() throws Exception {
        assertEquals(200, get("/login").statusCode());
    }

    @Test
    void transactionsRouteIsPublic() throws Exception {
        assertEquals(200, get("/transactions").statusCode());
    }

    @Test
    void actuatorHealthIsPublicWithoutDetails() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertEquals(200, response.statusCode());
        org.junit.jupiter.api.Assertions.assertFalse(response.body().contains("components"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
