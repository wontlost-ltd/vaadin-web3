package com.wontlost.web3.monitor.service.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;

import com.wontlost.web3.net.PublicAddressPolicy;
import com.wontlost.web3.monitor.service.MonitorProperties;
import com.wontlost.web3.monitor.service.api.ApiException;

/**
 * Webhook target policy (SSRF protection).
 * <p>
 * {@link #validate(String)} checks a URL when a merchant registers it. {@link #resolveAllowed(String)} is used as the
 * DNS resolver of the webhook HTTP client, so the addresses that are checked are exactly the addresses the connection
 * uses &mdash; a hostname cannot pass validation with a public address and then be re-resolved to an internal one
 * (DNS rebinding).
 */
public final class WebhookUrlPolicy {
    /** DNS lookup used by the policy; replaceable in tests. */
    @FunctionalInterface
    public interface Lookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final MonitorProperties properties;
    private final Lookup lookup;

    public WebhookUrlPolicy(MonitorProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    public WebhookUrlPolicy(MonitorProperties properties, Lookup lookup) {
        this.properties = Objects.requireNonNull(properties);
        this.lookup = Objects.requireNonNull(lookup);
    }

    /** Validates scheme and form, and that the host currently resolves only to allowed addresses. */
    public List<InetAddress> validate(String value) {
        try {
            URI uri = URI.create(value);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            if ((!https && !(http && properties.getWebhooks().isAllowInsecure())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Webhook URL must be an allowed HTTP(S) URL without user info or fragment");
            }
            return List.of(resolveAllowed(uri.getHost()));
        } catch (UnknownHostException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid webhook URL: " + exception.getMessage());
        }
    }

    /**
     * Resolves a webhook host and rejects it unless every address is allowed. Used at connection time by the delivery
     * client; throws {@link UnknownHostException} so the HTTP client aborts the connection attempt.
     */
    public InetAddress[] resolveAllowed(String host) throws UnknownHostException {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        InetAddress[] addresses = lookup.resolve(bare);
        if (addresses == null || addresses.length == 0) throw new UnknownHostException(bare);
        if (!properties.getWebhooks().isAllowPrivateTargets()) {
            for (InetAddress address : addresses) {
                if (!PublicAddressPolicy.isPublic(address)) {
                    throw new UnknownHostException("Webhook host " + bare + " resolves to a private or reserved address");
                }
            }
        }
        return addresses;
    }

}
