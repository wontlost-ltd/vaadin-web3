package com.wontlost.web3.gate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.router.QueryParameters;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.Erc20;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.chain.Tokens;
import com.wontlost.web3.siwe.Web3Session;

/**
 * Enforces token balance requirements on annotated routed views.
 * <p>
 * Balances are cached per (chain, token, address) for the configured TTL (30 seconds by default), so a holder
 * who transfers their tokens away may keep access for up to one TTL. Use a shorter TTL, or
 * {@link java.time.Duration#ZERO} to query on every navigation, when that window matters.
 */
public final class TokenGate implements BeforeEnterListener {
    private final ChainRegistry chains;
    private final long cacheMillis;
    private final int cacheCapacity;
    private final Map<BalanceKey, CachedBalance> balances = new ConcurrentHashMap<>();

    public TokenGate(ChainRegistry chains) { this(chains, Duration.ofSeconds(30), 10_000); }
    public TokenGate(ChainRegistry chains, Duration cacheTtl) {
        this(chains, cacheTtl, 10_000);
    }
    /** Creates a token gate with a bounded balance cache. */
    public TokenGate(ChainRegistry chains, Duration cacheTtl, int cacheCapacity) {
        this.chains = Objects.requireNonNull(chains);
        this.cacheMillis = Objects.requireNonNull(cacheTtl).toMillis();
        if (cacheCapacity < 1) throw new IllegalArgumentException("cacheCapacity must be positive");
        this.cacheCapacity = cacheCapacity;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        RequiresToken requirement = event.getNavigationTarget().getAnnotation(RequiresToken.class);
        if (requirement == null) return;
        var signIn = Web3Session.current();
        if (signIn.isEmpty()) {
            String path = event.getLocation().getPath();
            event.forwardTo(requirement.redirectTo(), QueryParameters.of("continue", path));
            return;
        }
        Decision decision = evaluate(requirement, signIn.get().address());
        if (decision == Decision.UNAVAILABLE) {
            event.rerouteToError(TokenGateUnavailableException.class, "Token balance cannot be verified temporarily");
        } else if (decision == Decision.INSUFFICIENT) {
            event.rerouteToError(TokenGateDeniedException.class,
                    "at least " + requirement.minBalance() + " " + resolve(requirement).symbol());
        }
    }

    /** 已登录用户的余额判定；解析代币、查询 decimals 或余额的任何失败都归为 UNAVAILABLE（故障关闭）。 */
    Decision evaluate(RequiresToken requirement, String address) {
        try {
            TokenInfo token = resolve(requirement);
            int decimals = requirement.decimals() < 0 ? Erc20.decimals(client(requirement.chainId()), token.address())
                    : requirement.decimals();
            BigInteger minimum = Tokens.toBaseUnits(new BigDecimal(requirement.minBalance()), decimals);
            return checkBalance(() -> balance(requirement.chainId(), token.address(), address), minimum);
        } catch (RuntimeException exception) {
            return Decision.UNAVAILABLE;
        }
    }

    /** Evaluates a gate without Vaadin or RPC dependencies. */
    public static Decision decide(boolean annotated, boolean signedIn, BigInteger balance, BigInteger minimum) {
        if (!annotated) return Decision.ALLOW;
        if (!signedIn) return Decision.SIGN_IN_REQUIRED;
        return balance.compareTo(minimum) >= 0 ? Decision.ALLOW : Decision.INSUFFICIENT;
    }

    static Decision checkBalance(java.util.function.Supplier<BigInteger> balanceQuery, BigInteger minimum) {
        try {
            return decide(true, true, balanceQuery.get(), minimum);
        } catch (RuntimeException exception) {
            return Decision.UNAVAILABLE;
        }
    }

    public enum Decision { ALLOW, SIGN_IN_REQUIRED, INSUFFICIENT, UNAVAILABLE }

    private TokenInfo resolve(RequiresToken requirement) {
        var builtIn = Tokens.find(requirement.token(), requirement.chainId());
        if (builtIn.isPresent()) return builtIn.get();
        if (Tokens.symbols().stream().anyMatch(symbol -> symbol.equalsIgnoreCase(requirement.token())))
            throw new IllegalArgumentException(requirement.token() + " is not registered on chain " + requirement.chainId());
        return new TokenInfo("TOKEN", requirement.chainId(), requirement.token(), requirement.decimals());
    }

    private EthRpcClient client(long chainId) {
        return chains.get(chainId).orElseThrow(() -> new IllegalStateException("No RPC registered for chain " + chainId));
    }

    private BigInteger balance(long chainId, String token, String address) {
        BalanceKey key = new BalanceKey(chainId, token.toLowerCase(), address.toLowerCase());
        long now = System.currentTimeMillis();
        CachedBalance cached = balances.get(key);
        if (cached != null && now - cached.fetchedAt < cacheMillis) return cached.value;
        BigInteger value = Erc20.balanceOf(client(chainId), token, address);
        synchronized (balances) {
            balances.entrySet().removeIf(entry -> now - entry.getValue().fetchedAt >= cacheMillis);
            balances.put(key, new CachedBalance(value, now));
            while (balances.size() > cacheCapacity) {
                BalanceKey oldest = balances.entrySet().stream()
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue().fetchedAt))
                        .orElseThrow().getKey();
                balances.remove(oldest);
            }
        }
        return value;
    }

    int cachedBalanceCount() { return balances.size(); }

    private record BalanceKey(long chainId, String token, String address) { }
    private record CachedBalance(BigInteger value, long fetchedAt) { }
}
