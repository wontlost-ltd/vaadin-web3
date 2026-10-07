package com.wontlost.web3.x402.payment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresPayment {
    String resourceId();
    String description() default "";
    String paywallRoute() default "paywall";
}
