package com.wontlost.web3.pro.screening;

import java.time.Instant;
import java.util.List;

/** Persists address screening decisions for operational review. */
public interface ScreeningAuditLog {
    /** Stores one decision. */
    void record(AuditEntry entry);
    /** Finds decisions for an address in the half-open time range. */
    List<AuditEntry> find(String address, Instant fromInclusive, Instant toExclusive);
    /** An immutable screening audit row. */
    record AuditEntry(String id, String address, boolean allowed, String reason, String source, Instant checkedAt) { }
}
