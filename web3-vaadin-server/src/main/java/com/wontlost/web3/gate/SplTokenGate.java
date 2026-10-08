package com.wontlost.web3.gate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.router.QueryParameters;
import com.wontlost.web3.identity.ChainAccount;
import com.wontlost.web3.siwe.Web3Session;
import com.wontlost.web3.solana.SolanaClusters;
import com.wontlost.web3.solana.SolanaRpcClient;

/**
 * Enforces {@link RequiresSplToken} on routed views. Balances are cached per (cluster, mint, owner) for the TTL
 * (30 seconds by default), so a holder who moves their tokens away may keep access for up to one TTL. Any failure to
 * read the balance denies entry with a temporary error rather than letting the visitor in.
 */
public final class SplTokenGate implements BeforeEnterListener {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(SplTokenGate.class);
    private final SolanaClusters clusters;
    private final BalanceCache<BalanceKey, BigDecimal> balances;

    public SplTokenGate(SolanaClusters clusters) {
        this(clusters, Duration.ofSeconds(30), 10_000);
    }

    /** Creates a gate with a bounded balance cache; {@link Duration#ZERO} reads the balance on every navigation. */
    public SplTokenGate(SolanaClusters clusters, Duration cacheTtl, int cacheCapacity) {
        this.clusters = Objects.requireNonNull(clusters);
        this.balances = new BalanceCache<>(cacheTtl, cacheCapacity);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        RequiresSplToken requirement = event.getNavigationTarget().getAnnotation(RequiresSplToken.class);
        if (requirement == null) return;
        var identity = Web3Session.currentIdentity();
        if (identity.isEmpty()) {
            event.forwardTo(requirement.redirectTo(), QueryParameters.of("continue", event.getLocation().getPath()));
            return;
        }
        ChainAccount account = identity.get().account();
        if (!onCluster(requirement, account)) {
            // 与 TokenGate 一致：只有身份不匹配时才提示需要哪种钱包
            event.rerouteToError(TokenGateDeniedException.class, "a Solana wallet on " + requirement.cluster().chainId()
                    + " holding at least " + requirement.minBalance() + " " + requirement.symbol());
            return;
        }
        TokenGate.Decision decision = evaluate(requirement, account);
        if (decision == TokenGate.Decision.UNAVAILABLE) {
            event.rerouteToError(TokenGateUnavailableException.class, "Token balance cannot be verified temporarily");
        } else if (decision == TokenGate.Decision.INSUFFICIENT) {
            event.rerouteToError(TokenGateDeniedException.class,
                    "at least " + requirement.minBalance() + " " + requirement.symbol());
        }
    }

    /**
     * 判定已登录账户：非 Solana 账户或其他集群的账户确定性拒绝且不发起 RPC；
     * 查询余额或解析门槛的任何失败都归为 UNAVAILABLE（故障关闭）。
     */
    TokenGate.Decision evaluate(RequiresSplToken requirement, ChainAccount account) {
        if (!onCluster(requirement, account)) return TokenGate.Decision.INSUFFICIENT;
        try {
            BigDecimal minimum = new BigDecimal(requirement.minBalance());
            BigDecimal held = balances.get(new BalanceKey(requirement.cluster(), requirement.mint(), account.address()),
                    () -> client(requirement).getTokenBalance(account.address(), requirement.mint()).uiAmount());
            return held.compareTo(minimum) >= 0 ? TokenGate.Decision.ALLOW : TokenGate.Decision.INSUFFICIENT;
        } catch (RuntimeException exception) {
            LOGGER.debug("SPL token gate could not read the balance of {} on {}", requirement.mint(),
                    requirement.cluster(), exception);
            return TokenGate.Decision.UNAVAILABLE;
        }
    }

    private static boolean onCluster(RequiresSplToken requirement, ChainAccount account) {
        return "solana".equals(account.namespace()) && requirement.cluster().reference().equals(account.reference());
    }

    private SolanaRpcClient client(RequiresSplToken requirement) {
        return clusters.get(requirement.cluster()).orElseThrow(
                () -> new IllegalStateException("No Solana RPC client registered for " + requirement.cluster()));
    }

    int cachedBalanceCount() {
        return balances.size();
    }

    private record BalanceKey(com.wontlost.web3.siws.SolanaCluster cluster, String mint, String owner) {
    }
}
