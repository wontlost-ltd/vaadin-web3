package com.wontlost.web3.gate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the minimum balance of a built-in token symbol or ERC-20 contract address required to enter a routed view. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresToken {
    long chainId();
    String token();
    String minBalance() default "1";
    int decimals() default -1;
    String redirectTo() default "login";
}
