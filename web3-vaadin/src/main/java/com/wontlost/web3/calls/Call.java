package com.wontlost.web3.calls;

import java.util.Map;

/**
 * A single EIP-5792 call.
 *
 * @param to           target address, or {@code null} for contract creation
 * @param data         calldata as a hex string, or {@code null}
 * @param value        value in wei as a hex string, or {@code null}
 * @param capabilities call-level capabilities, for example {@code Map.of("paymasterService", Map.of("optional", true))};
 *                     never {@code null} after construction
 */
public record Call(String to, String data, String value, Map<String, Object> capabilities) {

    public Call {
        capabilities = Capabilities.freeze(capabilities);
    }

    /** Creates a call without call-level capabilities. */
    public Call(String to, String data, String value) {
        this(to, data, value, null);
    }
}
