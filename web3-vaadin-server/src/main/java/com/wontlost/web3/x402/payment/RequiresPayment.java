package com.wontlost.web3.x402.payment;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresPayment {
    String resourceId() default "";
    String resource() default "";
    String description() default "";
    String paywallRoute() default "paywall";
    boolean idempotent() default false;
    boolean requireSiwx() default false;
}
