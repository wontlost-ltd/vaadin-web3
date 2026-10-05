package com.wontlost.web3.pro.payments;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringWriter;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.math.BigInteger;
import java.lang.reflect.Field;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wontlost.web3.pro.jdbc.ProSchema;
import com.wontlost.web3.pro.NonAutoCommitDataSource;
import com.wontlost.web3.pro.screening.JdbcScreeningAuditLog;
import com.wontlost.web3.pro.screening.ScreeningAuditLog;
import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.pay.InMemoryPaymentLedger;
import com.wontlost.web3.pay.PaymentRequest;
import com.wontlost.web3.pay.PaymentResult;
import com.wontlost.web3.pay.PaymentStatus;
import com.wontlost.web3.pay.StablecoinCheckout;

class PaymentRecordsTest {
    private JdbcDataSource source;
    @BeforeEach void setup() { source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:records" + System.nanoTime() + ";DB_CLOSE_DELAY=-1"); ProSchema.create(source); }

    @Test void recordStoreUpsertsAndFiltersWithPages() {
        JdbcPaymentRecordStore store = new JdbcPaymentRecordStore(source);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        store.upsert(new PaymentRecord("order",1,"USDC","0xabc","5.00","payer",null,"SUBMITTED",now,now));
        store.upsert(new PaymentRecord("order",1,"USDC","0xabc","5.00","payer","0xtx","CONFIRMED",now,now.plusSeconds(1)));
        assertEquals(1, store.count(now.minusSeconds(1), now.plusSeconds(10), "CONFIRMED"));
        assertEquals("0xtx", store.find(now.minusSeconds(1), now.plusSeconds(10), null, 0, 10).getFirst().txHash());
    }

    @Test void paymentAndAuditWritesCommitOnNonAutoCommitConnectionsAndUpsertConflictWorks() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        java.util.concurrent.atomic.AtomicBoolean insertedByCompetingConnection = new java.util.concurrent.atomic.AtomicBoolean();
        NonAutoCommitDataSource transactionalSource = new NonAutoCommitDataSource(source, sql -> {
            if (sql.startsWith("INSERT INTO web3_pro_payment_records") && insertedByCompetingConnection.compareAndSet(false, true)) {
                new JdbcPaymentRecordStore(source).upsert(new PaymentRecord("committed", 1, "USDC", "0xabc", "5.00",
                        "payer", null, "SUBMITTED", now, now));
            }
        });
        var store = new JdbcPaymentRecordStore(transactionalSource);
        store.upsert(new PaymentRecord("committed", 1, "USDC", "0xabc", "5.00", "payer", "0xtx", "CONFIRMED", now, now.plusSeconds(1)));
        assertTrue(insertedByCompetingConnection.get());
        assertEquals("0xtx", store.find(now.minusSeconds(1), now.plusSeconds(10), null, 0, 10).getFirst().txHash());

        JdbcScreeningAuditLog audit = new JdbcScreeningAuditLog(transactionalSource);
        audit.record(new ScreeningAuditLog.AuditEntry("audit-id", "0xaddress", true, "allowed", "test", now));
        assertEquals(1, audit.find("0xaddress", now.minusSeconds(1), now.plusSeconds(1)).size());
    }

    @Test void csvEscapesRfc4180AndNeutralizesFormulaCells() throws Exception {
        Instant now = Instant.EPOCH;
        List<PaymentRecord> rows = List.of(
                new PaymentRecord("=1+1",1,"@SUM","-2","\tformula","payer",null,"FAILED",now,now),
                new PaymentRecord("a,\"b\"\nc",1,"USDC",null,"1","payer",null,"FAILED",now,now),
                new PaymentRecord(" =1+1",1,"USDC",null,"1","payer",null,"FAILED",now,now),
                new PaymentRecord("\t=1",1,"USDC",null,"1","payer",null,"FAILED",now,now),
                new PaymentRecord("  @SUM(A1)",1,"USDC",null,"1","payer",null,"FAILED",now,now),
                new PaymentRecord("\u00a0-2",1,"USDC",null,"1","payer",null,"FAILED",now,now));
        StringWriter writer = new StringWriter(); PaymentRecordsCsv.write(writer, rows);
        assertTrue(writer.toString().contains("'=1+1"));
        assertTrue(writer.toString().contains("'@SUM"));
        assertTrue(writer.toString().contains("' =1+1"));
        assertTrue(writer.toString().contains("'\t=1"));
        assertTrue(writer.toString().contains("'  @SUM(A1)"));
        assertTrue(writer.toString().contains("'\u00a0-2"));
        assertTrue(writer.toString().contains("'-2"));
        assertTrue(writer.toString().contains("'\tformula"));
        assertTrue(writer.toString().contains("\"a,\"\"b\"\"\nc\""));
    }

    @Test void csvExportReadsAndWritesMultiplePagesWithCurrentFilters() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        List<Integer> offsets = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean verifyFilters = new java.util.concurrent.atomic.AtomicBoolean();
        PaymentRecordStore pagedStore = new PaymentRecordStore() {
            @Override public void upsert(PaymentRecord record) { throw new UnsupportedOperationException(); }
            @Override public List<PaymentRecord> find(Instant from, Instant to, String status, int offset, int limit) {
                if (verifyFilters.get()) {
                    assertEquals(now, from);
                    assertEquals(now.plusSeconds(86400), to);
                    assertEquals("CONFIRMED", status);
                }
                if (verifyFilters.get()) assertEquals(500, limit);
                offsets.add(offset);
                int count = Math.min(limit, Math.max(0, 501 - offset));
                return java.util.stream.IntStream.range(offset, offset + count)
                        .mapToObj(index -> new PaymentRecord("order-" + index, 1, "USDC", "0xabc", "1", "payer", null,
                                "CONFIRMED", now, now)).toList();
            }
            @Override public long count(Instant from, Instant to, String status) { return 501; }
        };
        PaymentsView view = new PaymentsView(pagedStore);
        Field fromField = PaymentsView.class.getDeclaredField("from"); fromField.setAccessible(true);
        ((com.vaadin.flow.component.datepicker.DatePicker) fromField.get(view)).setValue(java.time.LocalDate.of(2026, 1, 1));
        Field toField = PaymentsView.class.getDeclaredField("to"); toField.setAccessible(true);
        ((com.vaadin.flow.component.datepicker.DatePicker) toField.get(view)).setValue(java.time.LocalDate.of(2026, 1, 1));
        Field statusField = PaymentsView.class.getDeclaredField("status"); statusField.setAccessible(true);
        @SuppressWarnings("unchecked") var selector = (com.vaadin.flow.component.select.Select<String>) statusField.get(view);
        selector.setValue("CONFIRMED");
        verifyFilters.set(true);
        offsets.clear();
        StringWriter writer = new StringWriter();
        view.writeCsv(writer);
        assertEquals(List.of(0, 500), offsets);
        assertEquals(502, writer.toString().split("\r\n").length);
        assertTrue(writer.toString().contains("order-500"));
    }

    @Test void recorderUpsertsCheckoutEventsAndViewLoadsFilteredRowsWithoutBrowser() throws Exception {
        JdbcPaymentRecordStore store = new JdbcPaymentRecordStore(source);
        StablecoinCheckout checkout = new StablecoinCheckout(new ChainRegistry(), new InMemoryPaymentLedger(),
                "0x0000000000000000000000000000000000000001", new java.math.BigDecimal("1.25"));
        PaymentRecorder.attach(checkout, store);
        var token = com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow();
        var setSubmitted = StablecoinCheckout.class.getDeclaredMethod("setSubmittedTransactionForTest", String.class,
                String.class, PaymentRequest.class);
        setSubmitted.setAccessible(true);
        setSubmitted.invoke(checkout, "0xtransaction", "order-recorded",
                new PaymentRequest(1, token.address(), "0x0000000000000000000000000000000000000001", BigInteger.ONE));
        var apply = StablecoinCheckout.class.getDeclaredMethod("applyVerificationResult", PaymentResult.class);
        apply.setAccessible(true);
        apply.invoke(checkout, new PaymentResult(PaymentStatus.CONFIRMED, "0xtransaction", "0xpayer", BigInteger.ONE, 1));
        Instant start = Instant.now().minusSeconds(10);
        List<PaymentRecord> matches = store.find(start, Instant.now().plusSeconds(10), "CONFIRMED", 0, 10);
        assertEquals(1, matches.size());
        assertEquals("order-recorded", matches.getFirst().orderId());
        assertEquals("0xtransaction", matches.getFirst().txHash());

        // 超额付款：记录链上实付金额，而不是订单请求金额（账务必须如实）
        setSubmitted.invoke(checkout, "0xoverpaid", "order-overpaid",
                new PaymentRequest(1, token.address(), "0x0000000000000000000000000000000000000001", BigInteger.valueOf(1_250_000)));
        apply.invoke(checkout, new PaymentResult(PaymentStatus.CONFIRMED, "0xoverpaid", "0xpayer", BigInteger.valueOf(2_000_000), 1));
        PaymentRecord overpaid = store.find(start, Instant.now().plusSeconds(10), "CONFIRMED", 0, 10).stream()
                .filter(record -> record.orderId().equals("order-overpaid")).findFirst().orElseThrow();
        assertEquals("2.000000", overpaid.amount());

        PaymentsView view = new PaymentsView(store);
        Field status = PaymentsView.class.getDeclaredField("status"); status.setAccessible(true);
        @SuppressWarnings("unchecked") var selector = (com.vaadin.flow.component.select.Select<String>) status.get(view);
        selector.setValue("CONFIRMED");
        view.refresh();
        // 按时间倒序：两条 CONFIRMED 记录都在，且只显示 CONFIRMED
        assertEquals(java.util.Set.of("order-recorded", "order-overpaid"), view.visibleRecords().stream()
                .map(PaymentRecord::orderId).collect(java.util.stream.Collectors.toSet()));
    }
}
