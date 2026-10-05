package com.wontlost.web3.pro.screening;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.wontlost.web3.screening.AddressScreening;

/** Decorates a screening service and records each result. */
public final class AuditedScreening implements AddressScreening {
    private final AddressScreening delegate;
    private final ScreeningAuditLog auditLog;
    private final String source;
    private final Clock clock;
    /** Creates an audited decorator with the delegate class name as source. */
    public AuditedScreening(AddressScreening delegate, ScreeningAuditLog auditLog) {
        this(delegate, auditLog, delegate.getClass().getSimpleName(), Clock.systemUTC());
    }
    /** Creates an audited decorator with an explicit source label. */
    public AuditedScreening(AddressScreening delegate, ScreeningAuditLog auditLog, String source, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog");
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }
    @Override public ScreeningDecision screen(String address) {
        ScreeningDecision result;
        try {
            result = delegate.screen(address);
        } catch (RuntimeException failure) {
            // 筛查服务不可用时调用方按故障关闭处理（拒绝）；审计日志同样要留下这次"未能筛查即拒绝"的记录
            auditLog.record(new ScreeningAuditLog.AuditEntry(UUID.randomUUID().toString(), address, false,
                    "SCREENING_UNAVAILABLE: " + failure.getClass().getSimpleName()
                            + (failure.getMessage() == null ? "" : " " + failure.getMessage()), source, clock.instant()));
            throw failure;
        }
        auditLog.record(new ScreeningAuditLog.AuditEntry(UUID.randomUUID().toString(), address,
                result.allowed(), result.reason(), source, clock.instant()));
        return result;
    }
}
