package com.wontlost.web3.gate;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.solana.SolanaClusters;

/**
 * Automatically installs token route checks, including in Spring applications.
 * <p>
 * ERC-20/ERC-721 balances for {@link RequiresToken} are read through the {@link ChainRegistry} stored as a
 * {@code VaadinContext} attribute ({@code context.setAttribute(ChainRegistry.class, registry)}); SPL balances for
 * {@link RequiresSplToken} through the {@link SolanaClusters} attribute. Without the matching attribute, annotated
 * views are rejected as temporarily unavailable (HTTP 503) rather than opened.
 */
public final class TokenGateServiceInitListener implements VaadinServiceInitListener {
    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    private static void warnMissingRegistry() {
        if (WARNED.compareAndSet(false, true)) {
            org.slf4j.LoggerFactory.getLogger(TokenGateServiceInitListener.class).warn(
                    "No ChainRegistry is registered in the VaadinContext; @RequiresToken views will be rejected. "
                            + "Call context.setAttribute(ChainRegistry.class, registry) at startup.");
        }
    }

    /** 未注册 SolanaClusters 时用空注册表：带 @RequiresSplToken 的视图返回 503 而不是放行（故障关闭）。 */
    static SolanaClusters solanaClusters(SolanaClusters registered) {
        return registered == null ? new SolanaClusters() : registered;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        // Spring Boot 默认只扫描应用自身的包，不会发现本 add-on 里的错误视图，这里显式注册。
        // 两个视图均带 @DefaultErrorHandler，使用者的自定义视图始终优先（Vaadin 的合并规则）。
        ApplicationRouteRegistry.getInstance(event.getSource().getContext())
                .setErrorNavigationTargets(Set.of(TokenGateDeniedView.class, TokenGateUnavailableView.class));
        AtomicReference<TokenGate> tokenGate = new AtomicReference<>();
        VaadinService service = event.getSource();
        event.getSource().addUIInitListener(uiEvent -> {
            uiEvent.getUI().addBeforeEnterListener(tokenGate(service, tokenGate));
            uiEvent.getUI().addBeforeEnterListener(new SplTokenGate(solanaClusters(uiEvent.getUI().getSession()
                    .getService().getContext().getAttribute(SolanaClusters.class))));
        });
    }

    private static TokenGate tokenGate(VaadinService service, AtomicReference<TokenGate> cached) {
        TokenGate gate = cached.get();
        if (gate != null) return gate;
        synchronized (cached) {
            gate = cached.get();
            if (gate != null) return gate;
            ChainRegistry registry = service.getContext().getAttribute(ChainRegistry.class);
            if (registry == null) {
                warnMissingRegistry();
                registry = new ChainRegistry();
            }
            gate = new TokenGate(registry);
            cached.set(gate);
            return gate;
        }
    }
}
