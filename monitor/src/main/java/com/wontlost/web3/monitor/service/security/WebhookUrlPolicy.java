package com.wontlost.web3.monitor.service.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;

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
                if (isReserved(address)) {
                    throw new UnknownHostException("Webhook host " + bare + " resolves to a private or reserved address");
                }
            }
        }
        return addresses;
    }

    static boolean isReserved(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8 "this network"
                    || first == 100 && (second & 0xc0) == 64     // 100.64.0.0/10 运营商级 NAT
                    || first == 169 && second == 254            // 169.254.0.0/16 链路本地（云元数据）
                    || first == 192 && second == 0 && (b[2] & 0xff) == 0 // 192.0.0.0/24 IETF 协议分配
                    || first == 198 && (second & 0xfe) == 18     // 198.18.0.0/15 基准测试
                    || first >= 240;                             // 240.0.0.0/4 保留 + 广播
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;              // fc00::/7 唯一本地
            // 64:ff9b::/96 NAT64：可能映射到任意 IPv4（含内网），一律拒绝
            return b[0] == 0 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                    && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0 && b[8] == 0 && b[9] == 0 && b[10] == 0 && b[11] == 0;
        }
        return true;
    }
}
