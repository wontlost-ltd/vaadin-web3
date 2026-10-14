package com.wontlost.web3.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.PollEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.shared.Registration;

class UiPollingTest {
    @Test void runsEachSubscriptionAtItsOwnCadence() {
        AtomicLong now = new AtomicLong();
        UiPolling.setClock(now::get);
        try {
            TestUI ui = new TestUI();
            AtomicInteger shortRuns = new AtomicInteger();
            AtomicInteger longRuns = new AtomicInteger();
            Registration shortSubscription = UiPolling.register(ui, Duration.ofMillis(500), shortRuns::incrementAndGet);
            Registration longSubscription = UiPolling.register(ui, Duration.ofSeconds(3), longRuns::incrementAndGet);

            for (long time = 500; time <= 3000; time += 500) {
                now.set(time);
                ui.poll();
            }

            assertEquals(6, shortRuns.get());
            assertEquals(2, longRuns.get());
            shortSubscription.remove();
            longSubscription.remove();
        } finally {
            UiPolling.setClock(System::currentTimeMillis);
        }
    }

    @Test void measuresIntervalFromDelayedFirstPoll() {
        AtomicLong now = new AtomicLong();
        UiPolling.setClock(now::get);
        Registration subscription = null;
        try {
            TestUI ui = new TestUI();
            AtomicInteger runs = new AtomicInteger();
            subscription = UiPolling.register(ui, Duration.ofSeconds(3), runs::incrementAndGet);

            now.set(5_000);
            ui.poll();
            now.set(5_500);
            ui.poll();

            assertEquals(1, runs.get());
        } finally {
            if (subscription != null) subscription.remove();
            UiPolling.setClock(System::currentTimeMillis);
        }
    }

    @Test void choosesMinimumAndRestoresPreviousIntervalAfterFinalRemoval() {
        TestUI ui = new TestUI();
        ui.setPollInterval(9000);
        Registration slow = UiPolling.register(ui, Duration.ofSeconds(5), () -> { });
        Registration fast = UiPolling.register(ui, Duration.ofSeconds(2), () -> { });
        assertEquals(2000, ui.getPollInterval());
        assertEquals(1, ui.listenerCount());
        fast.remove();
        assertEquals(5000, ui.getPollInterval());
        slow.remove();
        assertEquals(9000, ui.getPollInterval());
        assertEquals(0, ui.listenerCount());
    }

    @Test void dispatchesToActiveSubscribersAndRemovesOnlySelectedSubscription() {
        AtomicLong now = new AtomicLong();
        UiPolling.setClock(now::get);
        try {
            TestUI ui = new TestUI();
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            Registration a = UiPolling.register(ui, Duration.ofSeconds(1), first::incrementAndGet);
            Registration b = UiPolling.register(ui, Duration.ofSeconds(3), second::incrementAndGet);
            ui.poll();
            assertEquals(1, first.get());
            assertEquals(1, second.get());
            a.remove();
            now.set(3000);
            ui.poll();
            assertEquals(1, first.get());
            assertEquals(2, second.get());
            b.remove();
        } finally {
            UiPolling.setClock(System::currentTimeMillis);
        }
    }

    @Test void rejectsNonPositiveIntervals() {
        assertThrows(IllegalArgumentException.class, () -> UiPolling.register(new TestUI(), Duration.ZERO, () -> { }));
    }

    private static final class TestUI extends UI {
        private int listenerCount() { return getListeners(PollEvent.class).size(); }
        private void poll() { fireEvent(new PollEvent(this, false)); }
    }
}
