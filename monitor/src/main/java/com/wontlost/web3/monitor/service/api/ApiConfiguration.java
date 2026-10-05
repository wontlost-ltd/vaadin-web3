package com.wontlost.web3.monitor.service.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.wontlost.web3.monitor.service.MonitorProperties;
import com.wontlost.web3.monitor.service.security.MerchantAuthentication;
import com.wontlost.web3.monitor.service.security.WebhookUrlPolicy;

@Configuration
public class ApiConfiguration implements WebMvcConfigurer {
    private final MerchantAuthentication auth;
    public ApiConfiguration(MerchantAuthentication auth) { this.auth = auth; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(auth).addPathPatterns("/v1/**"); }
    @Bean WebhookUrlPolicy webhookUrlPolicy(MonitorProperties properties) { return new WebhookUrlPolicy(properties); }
}
