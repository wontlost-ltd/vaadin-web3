package com.wontlost.web3.nft;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.wontlost.web3.net.PublicAddressPolicy;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;

/** 在连接期重新校验 DNS 答案、禁止自动重定向的 HTTPS 获取器。 */
public final class ApacheNftMetadataFetcher implements NftMetadataFetcher, AutoCloseable {
    private final Set<Integer> allowedPorts;
    private final NftDnsResolver lookup;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "web3-nft-metadata-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public ApacheNftMetadataFetcher() {
        this(Set.of(443));
    }

    public ApacheNftMetadataFetcher(Set<Integer> allowedPorts) {
        this(allowedPorts, InetAddress::getAllByName);
    }

    public ApacheNftMetadataFetcher(Set<Integer> allowedPorts, NftDnsResolver lookup) {
        this.allowedPorts = Set.copyOf(allowedPorts);
        this.lookup = java.util.Objects.requireNonNull(lookup);
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return resolveAllowed(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                return resolveAllowed(host)[0].getCanonicalHostName();
            }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        this.client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                .disableAutomaticRetries().disableContentCompression().build();
    }

    @Override
    public NftMetadataFetchResponse fetch(URI uri, Duration timeout, int maxBytes) {
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        if (!allowedPorts.contains(port)) throw new NftMetadataException(NftMetadataFailureCode.UNSAFE_TARGET);
        HttpGet request = new HttpGet(uri);
        Timeout timeoutValue = Timeout.ofMilliseconds(Math.max(1L, timeout.toMillis()));
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(timeoutValue)
                .setConnectTimeout(timeoutValue)
                .setResponseTimeout(timeoutValue).build());
        AtomicBoolean timedOut = new AtomicBoolean();
        var timeoutTask = timeoutScheduler.schedule(() -> {
            timedOut.set(true);
            request.cancel();
        }, Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        try (var response = client.execute(request)) {
            HttpEntity entity = response.getEntity();
            byte[] body = new byte[0];
            if (entity != null) {
                try (InputStream input = entity.getContent(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (output.size() + count > maxBytes) {
                            throw new NftMetadataException(NftMetadataFailureCode.RESPONSE_TOO_LARGE);
                        }
                        output.write(buffer, 0, count);
                    }
                    body = output.toByteArray();
                }
            }
            var contentType = response.getFirstHeader("Content-Type");
            var location = response.getFirstHeader("Location");
            return new NftMetadataFetchResponse(response.getCode(), contentType == null ? "" : contentType.getValue(),
                    location == null ? null : location.getValue(), body);
        } catch (NftMetadataException exception) {
            throw exception;
        } catch (Exception exception) {
            // Apache 可能包装连接期 DNS 拒绝，保留稳定的安全失败码。
            if (containsUnsafeAddress(exception)) {
                throw new NftMetadataException(NftMetadataFailureCode.UNSAFE_TARGET);
            }
            if (timedOut.get() || exception instanceof java.net.SocketTimeoutException
                    || exception.getCause() instanceof java.net.SocketTimeoutException) {
                throw new NftMetadataException(NftMetadataFailureCode.TIMEOUT);
            }
            throw new NftMetadataException(NftMetadataFailureCode.UNAVAILABLE);
        } finally {
            timeoutTask.cancel(false);
        }
    }

    @Override
    public void close() {
        timeoutScheduler.shutdownNow();
        try {
            client.close();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to close metadata HTTP client", exception);
        }
    }

    private InetAddress[] resolveAllowed(String host) throws java.net.UnknownHostException {
        InetAddress[] addresses;
        try {
            addresses = lookup.resolve(host);
        } catch (UnsafeAddressException exception) {
            throw exception;
        } catch (Exception exception) {
            java.net.UnknownHostException unavailable = new java.net.UnknownHostException();
            unavailable.initCause(exception);
            throw unavailable;
        }
        if (addresses == null || addresses.length == 0) {
            throw new java.net.UnknownHostException();
        }
        for (InetAddress address : addresses) {
            if (!PublicAddressPolicy.isPublic(address)) {
                // 校验的是 Apache 即将用于连接的这次解析结果，而不是先前预检的结果。
                throw new UnsafeAddressException();
            }
        }
        return addresses.clone();
    }

    private boolean containsUnsafeAddress(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof UnsafeAddressException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class UnsafeAddressException extends java.net.UnknownHostException {
        private static final long serialVersionUID = 1L;
    }
}
