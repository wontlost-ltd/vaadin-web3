package com.wontlost.web3.gate;

import java.util.Set;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.wontlost.web3.chain.ChainRegistry;

/** Automatically installs token route checks, including in Spring applications. */
public final class TokenGateServiceInitListener implements VaadinServiceInitListener {
    @Override
    public void serviceInit(ServiceInitEvent event) {
        // Spring Boot 默认只扫描应用自身的包，不会发现本 add-on 里的错误视图，这里显式注册。
        // 两个视图均带 @DefaultErrorHandler，使用者的自定义视图始终优先（Vaadin 的合并规则）。
        ApplicationRouteRegistry.getInstance(event.getSource().getContext())
                .setErrorNavigationTargets(Set.of(TokenGateDeniedView.class, TokenGateUnavailableView.class));
        event.getSource().addUIInitListener(uiEvent -> {
            ChainRegistry registry = uiEvent.getUI().getSession().getService().getContext()
                    .getAttribute(ChainRegistry.class);
            if (registry != null) uiEvent.getUI().addBeforeEnterListener(new TokenGate(registry));
        });
    }
}
