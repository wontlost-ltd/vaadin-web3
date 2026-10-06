package com.wontlost.web3.chain;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JSON-RPC transport with ordered endpoints, circuit breakers, and a sticky active endpoint. */
public final class FailoverJsonRpcTransport implements JsonRpcTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final List<EndpointState> endpoints;
    private final Config config;
    private final AtomicReference<EndpointState> active;

    /** Creates a transport with default circuit breaker settings. */
    public FailoverJsonRpcTransport(List<Endpoint> endpoints) { this(endpoints, Config.defaults()); }

    /** Creates a transport for endpoints in priority order. */
    public FailoverJsonRpcTransport(List<Endpoint> endpoints, Config config) {
        if (endpoints == null || endpoints.isEmpty()) throw new IllegalArgumentException("At least one endpoint is required");
        this.config = Objects.requireNonNull(config);
        this.endpoints = endpoints.stream().map(EndpointState::new).toList();
        this.active = new AtomicReference<>(this.endpoints.getFirst());
    }

    @Override
    public String send(String requestJson) throws IOException {
        return sendToEndpoints(requestJson, endpoints.size());
    }

    String sendRawTransactionRequest(String requestJson) throws IOException {
        return sendToEndpoints(requestJson, Math.min(3, endpoints.size()));
    }

    private String sendToEndpoints(String requestJson, int maxAttempts) throws IOException {
        List<EndpointState> candidates = candidates();
        List<String> errors = new ArrayList<>();
        int attempts = 0;
        for (EndpointState endpoint : candidates) {
            if (attempts >= maxAttempts) break;
            if (!endpoint.acquireProbe()) {
                errors.add(endpoint.endpoint.id() + ": " + endpoint.unavailableReason());
                continue;
            }
            attempts++;
            long started = System.nanoTime();
            try {
                String response = endpoint.endpoint.transport().send(requestJson);
                RpcFailure rpcFailure = rpcFailure(response);
                if (rpcFailure != null && shouldSwitch(rpcFailure.category)) {
                    endpoint.failed(rpcFailure.category.name(), rpcFailure.message, config);
                    errors.add(endpoint.endpoint.id() + ": " + rpcFailure.message);
                    continue;
                }
                endpoint.succeeded(Duration.ofNanos(System.nanoTime() - started));
                active.set(endpoint);
                probePrimaryForNextRequest(endpoint);
                return response;
            } catch (IOException exception) {
                String category = exception instanceof HttpJsonRpcTransport.JsonRpcHttpException http
                        ? http.status() == 429 ? "RATE_LIMITED"
                        : http.status() == 401 || http.status() == 403 ? "CONFIGURATION_ERROR" : "HTTP_" + http.status()
                        : "IO";
                if (!shouldSwitch(exception)) {
                    endpoint.recordError(category, exception.getMessage(), config.openDuration());
                    throw exception;
                }
                endpoint.failed(category, exception.getMessage(), config);
                errors.add(endpoint.endpoint.id() + ": " + summarize(exception.getMessage()));
            } finally {
                endpoint.releaseProbe();
            }
        }
        throw new IOException("All JSON-RPC endpoints are unavailable: " + String.join("; ", errors));
    }

    /**
     * Returns a view bound to one healthy endpoint for the duration of a multi-request operation; failures are
     * propagated without failover inside the view, but they move the active endpoint so the next view uses another.
     */
    public JsonRpcTransport pinned() {
        // 视图创建时选第一个健康端点；不能盲目取 active，否则主端点宕机后所有固定视图都卡在它上面，永远不切换
        EndpointState fixed = pinCandidate();
        return request -> {
            if (!fixed.acquireProbe()) {
                moveActiveAwayFrom(fixed);
                throw new IOException("Pinned JSON-RPC endpoint is unavailable: " + fixed.endpoint.id());
            }
            long started = System.nanoTime();
            try {
                String response = fixed.endpoint.transport().send(request);
                RpcFailure failure = rpcFailure(response);
                if (failure != null && shouldSwitch(failure.category)) {
                    fixed.failed(failure.category.name(), failure.message, config);
                    moveActiveAwayFrom(fixed);
                    throw new PinnedRpcFailure("Pinned JSON-RPC endpoint failed: " + failure.message);
                }
                fixed.succeeded(Duration.ofNanos(System.nanoTime() - started));
                // 固定视图成功后同样安排主端点恢复探测：只影响后续操作的 active，不改变本视图的节点
                probePrimaryForNextRequest(fixed);
                return response;
            } catch (PinnedRpcFailure exception) {
                throw exception; // 已按原始类别记录过一次失败，避免在 IO 分支重复计数
            } catch (IOException exception) {
                if (shouldSwitch(exception)) {
                    fixed.failed("IO", exception.getMessage(), config);
                    moveActiveAwayFrom(fixed);
                }
                throw exception;
            } finally { fixed.releaseProbe(); }
        };
    }

    /** 固定视图中已记录的暂态 JSON-RPC 失败；与传输层 IOException 区分，以免重复计入熔断。 */
    private static final class PinnedRpcFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private PinnedRpcFailure(String message) { super(message); }
    }

    private EndpointState pinCandidate() {
        for (EndpointState candidate : candidates()) if (candidate.state() == State.CLOSED) return candidate;
        return active.get();
    }

    /** 固定视图上的失败让 active 让位给下一个未熔断端点，后续视图（例如下一次轮询）即可切换。 */
    private void moveActiveAwayFrom(EndpointState failed) {
        if (active.get() != failed) return;
        for (EndpointState endpoint : endpoints) {
            if (endpoint != failed && endpoint.state() != State.OPEN) {
                active.compareAndSet(failed, endpoint);
                return;
            }
        }
    }

    /** Returns an immutable snapshot of endpoint health. */
    public List<Health> healthSnapshot() {
        return Collections.unmodifiableList(endpoints.stream().map(EndpointState::health).toList());
    }

    Config config() { return config; }

    void rejectActiveForLag(String message) {
        EndpointState current = active.get();
        current.forceOpen("TRANSIENT_NODE", message, config.openDuration());
        for (EndpointState endpoint : endpoints) {
            if (endpoint != current && endpoint.state() != State.OPEN) {
                active.compareAndSet(current, endpoint);
                break;
            }
        }
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (EndpointState endpoint : endpoints) try { endpoint.endpoint.transport().close(); }
        catch (Exception exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
        if (failure != null) throw failure;
    }

    private List<EndpointState> candidates() {
        List<EndpointState> result = new ArrayList<>();
        EndpointState current = active.get();
        result.add(current);
        for (EndpointState endpoint : endpoints) if (!result.contains(endpoint)) result.add(endpoint);
        return result;
    }

    private void probePrimaryForNextRequest(EndpointState respondingEndpoint) {
        EndpointState primary = endpoints.getFirst();
        if (respondingEndpoint == primary || !primary.acquireRecoveryProbe()) return;
        try {
            String response = primary.endpoint.transport().send(
                    "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"eth_chainId\",\"params\":[]}");
            JsonNode parsed = MAPPER.readTree(response);
            JsonNode error = parsed.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                EthRpcException rpcError = new EthRpcException(error.path("code").asInt(),
                        error.path("message").asString("JSON-RPC error"),
                        error.path("data").isMissingNode() || error.path("data").isNull()
                                ? null : error.path("data").toString());
                primary.failed(rpcError.getCategory().name(), rpcError.getMessage(), config);
                return;
            }
            if (parsed.path("result").isMissingNode()) throw new IOException("Primary endpoint probe returned no result");
            primary.succeeded(Duration.ZERO);
            active.set(primary);
        } catch (IOException | RuntimeException exception) {
            primary.failed("IO", exception.getMessage(), config);
        } finally {
            primary.releaseProbe();
        }
    }

    private boolean shouldSwitch(IOException exception) {
        if (!(exception instanceof HttpJsonRpcTransport.JsonRpcHttpException http)) return true;
        return http.status() == 408 || http.status() == 429 || http.status() >= 500;
    }

    private boolean shouldSwitch(EthRpcException.Category category) {
        return category == EthRpcException.Category.TRANSIENT_NODE || category == EthRpcException.Category.RATE_LIMITED;
    }

    private static RpcFailure rpcFailure(String response) throws IOException {
        try {
            JsonNode error = MAPPER.readTree(response).path("error");
            if (error.isMissingNode() || error.isNull()) return null;
            int code = error.path("code").asInt();
            String message = error.path("message").asString("JSON-RPC error");
            String data = error.path("data").isMissingNode() || error.path("data").isNull()
                    ? null : error.path("data").toString();
            return new RpcFailure(new EthRpcException(code, message, data).getCategory(), message);
        } catch (RuntimeException exception) { throw new IOException("Invalid JSON-RPC response", exception); }
    }

    private static String summarize(String message) {
        if (message == null) return "I/O failure";
        return message.length() <= 160 ? message : message.substring(0, 157) + "...";
    }

    /** An endpoint transport and its caller-provided display identifier. */
    public record Endpoint(String id, JsonRpcTransport transport) {
        public Endpoint { Objects.requireNonNull(id); Objects.requireNonNull(transport); }
    }
    /** Circuit breaker and consistency settings. */
    public record Config(int failureThreshold, Duration openDuration, int lagTolerance) {
        public Config {
            if (failureThreshold < 1 || lagTolerance < 0) throw new IllegalArgumentException("Invalid failover config");
            Objects.requireNonNull(openDuration);
        }
        /** Returns the default threshold, cooldown, and tolerated head lag. */
        public static Config defaults() { return new Config(3, Duration.ofSeconds(15), 3); }
    }
    /** Circuit breaker state for one endpoint. */
    public enum State { CLOSED, OPEN, HALF_OPEN }
    /** Immutable health details for one configured endpoint. */
    public record Health(String id, State state, int consecutiveFailures, String lastErrorCategory,
                        String lastErrorMessage, Duration lastLatency, Instant lastSuccessAt, Instant nextProbeAt) { }
    private record RpcFailure(EthRpcException.Category category, String message) { }

    private static final class EndpointState {
        private final Endpoint endpoint;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicInteger probing = new AtomicInteger();
        private final ThreadLocal<Boolean> ownsProbe = ThreadLocal.withInitial(() -> false);
        private volatile State state = State.CLOSED;
        private volatile String errorCategory;
        private volatile String errorMessage;
        private volatile Duration latency;
        private volatile Instant lastSuccess;
        private volatile Instant nextProbeAt = Instant.EPOCH;
        private EndpointState(Endpoint endpoint) { this.endpoint = endpoint; }
        private State state() { return state; }
        private boolean acquireProbe() {
            synchronized (this) {
                if (state == State.OPEN) {
                    if (Instant.now().isBefore(nextProbeAt)) return false;
                    state = State.HALF_OPEN;
                }
                if (state == State.CLOSED) return true;
                boolean acquired = probing.compareAndSet(0, 1);
                ownsProbe.set(acquired);
                return acquired;
            }
        }
        private synchronized boolean acquireRecoveryProbe() {
            if (state == State.OPEN || state == State.HALF_OPEN) return acquireProbe();
            if (Instant.now().isBefore(nextProbeAt)) return false;
            boolean acquired = probing.compareAndSet(0, 1);
            ownsProbe.set(acquired);
            return acquired;
        }
        private void releaseProbe() {
            if (ownsProbe.get()) probing.set(0);
            ownsProbe.remove();
        }
        private synchronized void failed(String category, String message, Config config) {
            errorCategory = category;
            errorMessage = summarize(message);
            int failureCount = failures.incrementAndGet();
            if (state == State.HALF_OPEN || failureCount >= config.failureThreshold()) {
                state = State.OPEN;
                nextProbeAt = Instant.now().plus(config.openDuration());
            } else nextProbeAt = Instant.now().plus(config.openDuration());
        }
        private synchronized void succeeded(Duration elapsed) {
            failures.set(0);
            state = State.CLOSED;
            errorCategory = null;
            errorMessage = null;
            latency = elapsed;
            lastSuccess = Instant.now();
            nextProbeAt = Instant.EPOCH;
        }
        private synchronized void recordError(String category, String message, Duration duration) {
            errorCategory = category;
            errorMessage = summarize(message);
            nextProbeAt = Instant.now().plus(duration);
        }
        private synchronized void forceOpen(String category, String message, Duration duration) {
            failures.incrementAndGet();
            errorCategory = category;
            errorMessage = summarize(message);
            state = State.OPEN;
            nextProbeAt = Instant.now().plus(duration);
        }
        private String unavailableReason() {
            return errorMessage == null ? "circuit " + state : errorCategory + ": " + errorMessage;
        }
        private Health health() {
            return new Health(endpoint.id(), state, failures.get(), errorCategory, errorMessage,
                    latency, lastSuccess, nextProbeAt.isAfter(Instant.now()) ? nextProbeAt : null);
        }
    }
}
