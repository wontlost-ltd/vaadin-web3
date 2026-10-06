package com.wontlost.web3.calls;

/** Controls whether unsupported batch calls may be sent as separate transactions. */
public enum FallbackPolicy {
    NEVER,
    ALLOW_NON_ATOMIC
}
