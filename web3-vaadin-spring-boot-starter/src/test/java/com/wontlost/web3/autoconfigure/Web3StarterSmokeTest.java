package com.wontlost.web3.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;
import com.wontlost.web3.ServerWallet;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = Web3StarterSmokeTest.TestApplication.class)
class Web3StarterSmokeTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void startsVaadinApplicationWithoutDefaultServerWallet() {
        assertThat(context.getBeansOfType(ServerWallet.class)).isEmpty();
        assertThat(context.getBeansOfType(com.vaadin.flow.server.VaadinServiceInitListener.class)).isNotEmpty();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SmokeView.class)
    static class TestApplication { }

    @Route("starter-smoke")
    public static class SmokeView extends Div { }
}
