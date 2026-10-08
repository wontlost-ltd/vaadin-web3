package com.wontlost.web3.solana.wallet;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinContext;
import com.vaadin.flow.server.VaadinService;
import com.wontlost.web3.solana.SolanaBackgroundExecutor;

/** Resolves the executor in explicit, application-context, then common-pool order. */
final class SolanaBackgroundExecutorResolver {
    private SolanaBackgroundExecutorResolver() {
    }

    static Executor resolve(Executor explicit, VaadinService currentService, UI ui) {
        if (explicit != null) return explicit;
        VaadinService service = currentService;
        if (service == null && ui != null && ui.getSession() != null) service = ui.getSession().getService();
        return resolve(explicit, service == null ? null : service.getContext());
    }

    static Executor resolve(Executor explicit, VaadinContext context) {
        if (explicit != null) return explicit;
        SolanaBackgroundExecutor registered = SolanaBackgroundExecutor.find(context);
        return registered == null ? ForkJoinPool.commonPool() : registered.executor();
    }

    static Executor resolve(Executor explicit, UI ui) {
        return resolve(explicit, VaadinService.getCurrent(), ui);
    }
}
