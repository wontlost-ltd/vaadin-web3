package com.wontlost.web3.demo;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import com.wontlost.web3.autoconfigure.security.Web3LogoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain web3SecurityFilterChain(HttpSecurity http, Web3LogoutHandler web3LogoutHandler)
            throws Exception {
        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> configurer
                .loginView(LoginView.class)
                .addLogoutHandler(web3LogoutHandler));
        return http.build();
    }
}
