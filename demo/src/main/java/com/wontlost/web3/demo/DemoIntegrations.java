package com.wontlost.web3.demo;

import com.wontlost.web3.monitor.PaymentMonitorClient;
import com.wontlost.web3.onramp.OnrampProvider;

/**
 * Optional integrations configured from application properties; each field is {@code null} when not configured.
 * <p>
 * A plain holder bean is used on purpose: Spring treats an injection point of type {@code Optional<X>} as "an optional
 * bean of type X", so a bean declared as {@code Optional<X>} is never injected there and silently becomes empty.
 */
public record DemoIntegrations(OnrampProvider onrampProvider, PaymentMonitorClient monitorClient) { }
