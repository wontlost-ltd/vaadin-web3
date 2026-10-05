package com.wontlost.web3.onramp;

import java.io.Serializable;
import java.net.URI;

import com.wontlost.web3.chain.TokenInfo;

/** Creates a hosted card-purchase session for a supported token. */
public interface OnrampProvider extends Serializable {
    /** Returns the provider's display name. */
    String name();
    /** Checks the provider's current supported-token catalog. */
    boolean supports(TokenInfo token);
    /** Creates a one-time URL for the browser to open. */
    URI createSession(OnrampOrder order);
    /** Returns whether provider-issued test tokens are used. */
    boolean isTestEnvironment();

    /**
     * Non-blocking variant of {@link #supports(TokenInfo)} for UI construction: returns empty while the provider's
     * currency catalog has not been loaded yet (a background load is started), so views never wait on the network.
     * The default implementation delegates to {@link #supports(TokenInfo)}.
     */
    default java.util.Optional<Boolean> supportsIfLoaded(TokenInfo token) {
        return java.util.Optional.of(supports(token));
    }

    /** Starts loading the currency catalog in the background; called when the provider is registered. */
    default void prefetch() { }
}
