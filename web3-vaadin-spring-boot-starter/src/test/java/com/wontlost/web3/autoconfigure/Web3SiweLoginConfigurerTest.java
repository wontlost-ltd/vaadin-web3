package com.wontlost.web3.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.wontlost.web3.autoconfigure.Web3Properties.Siwe;
import com.wontlost.web3.siwe.InMemoryNonceStore;
import com.wontlost.web3.siwe.SiweLogin;

class Web3SiweLoginConfigurerTest {
    @Test
    void appliesRequestCustomizersInOrderAndPassesRequest() {
        List<String> applied = new ArrayList<>();
        MockHttpServletRequest request = new MockHttpServletRequest();
        SiweLoginCustomizer first = (login, actualRequest) -> {
            assertThat(actualRequest).isSameAs(request);
            applied.add("first");
        };
        SiweLoginCustomizer second = (login, actualRequest) -> {
            assertThat(actualRequest).isSameAs(request);
            applied.add("second");
        };
        Web3SiweLoginConfigurer configurer = new Web3SiweLoginConfigurer(new Siwe(), List.of(),
                List.of(first, second));

        SiweLogin login = new SiweLogin(new InMemoryNonceStore());
        assertThat(configurer.configure(login, request)).isSameAs(login);
        assertThat(applied).containsExactly("first", "second");
    }

    @Test
    void legacyConfigurePassesNullRequest() {
        List<jakarta.servlet.http.HttpServletRequest> requests = new ArrayList<>();
        Web3SiweLoginConfigurer configurer = new Web3SiweLoginConfigurer(new Siwe(), List.of(),
                List.of((login, request) -> requests.add(request)));

        configurer.configure(new SiweLogin(new InMemoryNonceStore()));

        assertThat(requests).containsExactly((jakarta.servlet.http.HttpServletRequest) null);
    }
}
