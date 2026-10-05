package com.wontlost.web3.pro.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.vaadin.flow.server.VaadinContext;

/** Stores payment records and exposes bounded time/status queries. */
public interface PaymentRecordStore {
    /** Inserts or updates a record identified by order id. */
    void upsert(PaymentRecord record);
    /** Returns records in the half-open time range, optionally filtered by status. */
    List<PaymentRecord> find(Instant fromInclusive, Instant toExclusive, String status, int offset, int limit);
    /** Returns the total matching count for the requested filters. */
    long count(Instant fromInclusive, Instant toExclusive, String status);

    /** Registers the application-scoped store for component restoration after UI deserialization. */
    static void register(VaadinContext context, PaymentRecordStore store) {
        PaymentRecordStore current = context.getAttribute(PaymentRecordStore.class);
        if (current != null && current != store) throw new IllegalStateException("A different PaymentRecordStore is already registered");
        context.setAttribute(PaymentRecordStore.class, store);
    }
    /** Finds the application-scoped store. */
    static Optional<PaymentRecordStore> find(VaadinContext context) {
        return Optional.ofNullable(context == null ? null : context.getAttribute(PaymentRecordStore.class));
    }
}
