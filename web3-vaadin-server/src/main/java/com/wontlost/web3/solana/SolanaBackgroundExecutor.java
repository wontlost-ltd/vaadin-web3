package com.wontlost.web3.solana;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.vaadin.flow.server.VaadinContext;

/** An application-scoped executor for Solana background RPC work. */
public final class SolanaBackgroundExecutor implements AutoCloseable {
    private final Executor executor;
    private final ThreadPoolExecutor managedPool;

    /** Wraps an application-owned executor; this wrapper will not shut it down. */
    public SolanaBackgroundExecutor(Executor executor) {
        this(executor, null);
    }

    private SolanaBackgroundExecutor(Executor executor, ThreadPoolExecutor managedPool) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.managedPool = managedPool;
    }

    /** Returns the executor used for background Solana work. */
    public Executor executor() {
        return executor;
    }

    /** Registers the application's executor in the Vaadin context. */
    public static void register(VaadinContext context, SolanaBackgroundExecutor backgroundExecutor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
        SolanaBackgroundExecutor current = context.getAttribute(SolanaBackgroundExecutor.class);
        if (current != null && current != backgroundExecutor) {
            throw new IllegalStateException("A different SolanaBackgroundExecutor is already registered");
        }
        context.setAttribute(SolanaBackgroundExecutor.class, backgroundExecutor);
    }

    /** Returns the application's executor, or {@code null} when none is registered. */
    public static SolanaBackgroundExecutor find(VaadinContext context) {
        return context == null ? null : context.getAttribute(SolanaBackgroundExecutor.class);
    }

    /** Creates an owned bounded pool for starter auto-configuration. */
    public static SolanaBackgroundExecutor managed(ThreadPoolExecutor pool) {
        Objects.requireNonNull(pool, "pool");
        if (!(pool.getQueue() instanceof ArrayBlockingQueue<?>)) {
            throw new IllegalArgumentException("managed Solana executor must use a bounded array queue");
        }
        return new SolanaBackgroundExecutor(pool, pool);
    }

    @Override
    public void close() {
        if (managedPool == null) return;
        managedPool.shutdown();
        try {
            if (!managedPool.awaitTermination(3, TimeUnit.SECONDS)) managedPool.shutdownNow();
        } catch (InterruptedException exception) {
            managedPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
