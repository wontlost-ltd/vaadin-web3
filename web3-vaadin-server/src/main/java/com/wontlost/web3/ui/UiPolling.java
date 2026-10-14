package com.wontlost.web3.ui;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;

/** Coordinates polling subscriptions for one Vaadin UI. Calls must be made while holding the UI lock. */
public final class UiPolling {
    private static final String KEY = UiPolling.class.getName() + ".registry";
    private static final AtomicLong IDS = new AtomicLong();
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private UiPolling() { }

    /** Registers a callback and returns a handle that removes only this subscription. */
    public static Registration register(UI ui, Duration interval, Runnable onPoll) {
        if (ui == null || interval == null || onPoll == null) throw new NullPointerException();
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        Registry registry = (Registry) ComponentUtil.getData(ui, KEY);
        if (registry == null) {
            registry = new Registry(ui.getPollInterval());
            Registry created = registry;
            created.listener = ui.addPollListener(event -> created.runDueCallbacks());
            ComponentUtil.setData(ui, KEY, registry);
        }
        long id = IDS.incrementAndGet();
        synchronized (registry) {
            registry.entries.put(id, new Entry(interval, onPoll));
            update(ui, registry);
        }
        Registry captured = registry;
        return () -> {
            synchronized (captured) {
                if (captured.entries.remove(id) == null) return;
                if (captured.entries.isEmpty()) {
                    captured.listener.remove();
                    ui.setPollInterval(captured.previousInterval);
                    ComponentUtil.setData(ui, KEY, null);
                } else update(ui, captured);
            }
        };
    }

    private static void update(UI ui, Registry registry) {
        long minimum = registry.entries.values().stream().mapToLong(entry -> entry.interval.toMillis()).min().orElseThrow();
        ui.setPollInterval((int) Math.min(Integer.MAX_VALUE, Math.max(1, minimum)));
    }

    static void setClock(LongSupplier value) { clock = java.util.Objects.requireNonNull(value); }

    private static final class Entry {
        private final Duration interval;
        private final Runnable callback;
        private long lastRun;
        private boolean hasRun;

        private Entry(Duration interval, Runnable callback) {
            this.interval = interval;
            this.callback = callback;
        }

        private boolean isDue(long now) {
            long intervalMillis = interval.toMillis();
            long threshold = intervalMillis - intervalMillis / 6;
            return !hasRun || now - lastRun >= threshold;
        }

        private void markRun(long now) {
            lastRun = now;
            hasRun = true;
        }
    }

    private static final class Registry {
        private final int previousInterval;
        private final Map<Long, Entry> entries = new LinkedHashMap<>();
        private Registration listener;
        private Registry(int previousInterval) { this.previousInterval = previousInterval; }
        private void runDueCallbacks() {
            long now = clock.getAsLong();
            dueCallbacks(now).forEach(Runnable::run);
        }

        private synchronized java.util.List<Runnable> dueCallbacks(long now) {
            return entries.values().stream().filter(entry -> entry.isDue(now)).peek(entry -> {
                entry.markRun(now);
            }).map(entry -> entry.callback).toList();
        }
    }
}
