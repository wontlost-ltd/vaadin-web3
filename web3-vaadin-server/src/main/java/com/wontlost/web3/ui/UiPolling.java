package com.wontlost.web3.ui;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;

/** Coordinates polling subscriptions for one Vaadin UI. Calls must be made while holding the UI lock. */
public final class UiPolling {
    private static final String KEY = UiPolling.class.getName() + ".registry";
    private static final AtomicLong IDS = new AtomicLong();
    private UiPolling() { }

    /** Registers a callback and returns a handle that removes only this subscription. */
    public static Registration register(UI ui, Duration interval, Runnable onPoll) {
        if (ui == null || interval == null || onPoll == null) throw new NullPointerException();
        if (interval.isZero() || interval.isNegative()) throw new IllegalArgumentException("interval must be positive");
        Registry registry = (Registry) ComponentUtil.getData(ui, KEY);
        if (registry == null) {
            registry = new Registry(ui.getPollInterval());
            Registry created = registry;
            created.listener = ui.addPollListener(event -> created.callbacks().forEach(Runnable::run));
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

    private record Entry(Duration interval, Runnable callback) { }
    private static final class Registry {
        private final int previousInterval;
        private final Map<Long, Entry> entries = new LinkedHashMap<>();
        private Registration listener;
        private Registry(int previousInterval) { this.previousInterval = previousInterval; }
        private synchronized java.util.List<Runnable> callbacks() {
            return entries.values().stream().map(Entry::callback).toList();
        }
    }
}
