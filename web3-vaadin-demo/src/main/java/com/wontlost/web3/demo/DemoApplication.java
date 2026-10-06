package com.wontlost.web3.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * Vaadin Web3 插件示例应用。
 * <p>
 * Run with {@code mvn spring-boot:run -pl web3-vaadin-demo} and open
 * {@code http://localhost:8080}; the demo profile also provides a local
 * Development wallet when Anvil is running.
 */
@Push
@StyleSheet(Lumo.STYLESHEET)
@SpringBootApplication
public class DemoApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
