package com.wontlost.web3.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * Demo application for the Vaadin web3 add-on.
 * <p>
 * Run with {@code mvn spring-boot:run -pl demo} and open
 * {@code http://localhost:8080} in a browser with a wallet extension
 * (e.g. MetaMask) installed.
 */
@Push
@StyleSheet(Lumo.STYLESHEET)
@SpringBootApplication
public class DemoApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
