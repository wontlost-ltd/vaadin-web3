package com.wontlost.web3.x402.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;
import com.vaadin.flow.server.VaadinSession;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.x402.protocol.Eip3009Payload;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequired;
import com.wontlost.web3.x402.protocol.PaymentRequirements;
import com.wontlost.web3.x402.protocol.TransferAuthorization;
import com.wontlost.web3.x402.protocol.X402Resource;

class X402PaywallFlowTest {
    private static final String ADDRESS_A = "0x0000000000000000000000000000000000000001";
    private static final String ADDRESS_B = "0x0000000000000000000000000000000000000002";
    private TestUI ui;

    @AfterEach void clearCurrent() { UI.setCurrent(null); VaadinSession.setCurrent(null); }

    @Test void rejectionReturnsToRetryStateAndDoesNotCreateRepeatedAttempts() {
        FakePayments payments = new FakePayments();
        X402Paywall wall = create(payments, new TestWallet(ADDRESS_A, "0x7a69", CompletableFuture.failedFuture(new Web3Connect.Web3Exception(4001, "rejected"))));
        wall.payNowForTest();
        assertEquals(PaywallState.REJECTED, wall.getState());
        assertEquals(1, payments.prepares.get());
        wall.payNowForTest();
        assertEquals(2, payments.prepares.get());
        assertEquals(PaywallState.REJECTED, wall.getState());
    }

    @Test void wrongNetworkAndMismatchedSessionAccountNeverPrepareOrSign() {
        FakePayments payments = new FakePayments();
        TestWallet wrongChain = new TestWallet(ADDRESS_A, "0x1", CompletableFuture.completedFuture("sig"));
        X402Paywall wall = create(payments, wrongChain);
        wall.payNowForTest();
        assertEquals(PaywallState.ERROR, wall.getState());
        assertTrue(wall.getErrorMessageForTest().contains("Switch to the required network"));
        assertEquals(0, payments.prepares.get());
        TestWallet other = new TestWallet(ADDRESS_B, "0x7a69", CompletableFuture.completedFuture("sig"));
        X402Paywall otherWall = create(payments, other);
        otherWall.payNowForTest();
        assertEquals(0, payments.prepares.get());
        assertEquals(0, other.signatures.get());
        assertFalse(otherWall.isPaymentEnabledForTest());
    }

    @Test void anonymousPaymentWallRequiresSiweBeforePreparing() {
        FakePayments payments = new FakePayments();
        X402Paywall wall = create(payments,
                new TestWallet(ADDRESS_A, "0x7a69", CompletableFuture.completedFuture("sig")), () -> null);
        wall.payNowForTest();
        assertEquals(PaywallState.ERROR, wall.getState());
        assertTrue(wall.getErrorMessageForTest().contains("Sign in with SIWE"));
        assertEquals(0, payments.prepares.get());
    }

    @Test void pendingShowsHashBlocksRetryAndReconcileSettlementUnlocks() {
        FakePayments payments = new FakePayments();
        payments.settle = new PaymentOutcome("id", PaymentStatus.PENDING, "0xdeadbeef", null);
        payments.reconciled = new PaymentOutcome("id", PaymentStatus.SETTLED, "0xdeadbeef", null);
        X402Paywall wall = create(payments, new TestWallet(ADDRESS_A, "0x7a69", CompletableFuture.completedFuture("sig"))).setReturnTarget("paid/article");
        wall.payNowForTest();
        assertEquals(PaywallState.SETTLEMENT_PENDING, wall.getState());
        assertEquals("0xdeadbeef", wall.getTransactionHashForTest());
        assertTrue(wall.getStatusMessageForTest().contains("Do not pay again"));
        assertFalse(wall.isPaymentEnabledForTest());
        assertEquals(AccessDecision.PAYMENT_PENDING, payments.hasAccess("demo-article", ADDRESS_A));
        wall.payNowForTest();
        assertEquals(1, payments.prepares.get());
        assertEquals(AccessDecision.PAYMENT_PENDING, payments.hasAccess("demo-article", ADDRESS_A));
        wall.reconcileForTest();
        assertEquals(PaywallState.PAID, wall.getState());
        assertEquals(1, payments.reconciles.get());
        assertEquals(AccessDecision.ALLOW, payments.hasAccess("demo-article", ADDRESS_A));
        assertEquals("paid/article", ui.navigatedTo);
    }

    @Test void detachedSignatureCallbackDoesNotSettleOrMutateUi() {
        FakePayments payments = new FakePayments();
        CompletableFuture<String> signature = new CompletableFuture<>();
        X402Paywall wall = create(payments, new TestWallet(ADDRESS_A, "0x7a69", signature));
        wall.payNowForTest();
        assertEquals(PaywallState.SIGNING, wall.getState());
        ui.remove(wall);
        signature.complete("sig");
        assertEquals(PaywallState.SIGNING, wall.getState());
        assertEquals(0, payments.settlements.get());
    }

    @Test void attachRestoresPendingPaymentWithoutOfferingAnotherSignature() {
        FakePayments payments = new FakePayments();
        payments.accessStatus = PaymentStatus.PENDING;
        payments.latest = new PaymentOutcome("pending-id", PaymentStatus.PENDING, "0xpending-hash", "settlement_pending");
        TestWallet wallet = new TestWallet(ADDRESS_A, "0x7a69", CompletableFuture.completedFuture("sig"));
        X402Paywall wall = create(payments, wallet);
        wall.setReturnTarget("paid/article");
        ui.remove(wall);
        ui.add(wall);

        assertEquals(PaywallState.SETTLEMENT_PENDING, wall.getState());
        assertEquals("0xpending-hash", wall.getTransactionHashForTest());
        assertFalse(wall.isPaymentEnabledForTest());
        wall.payNowForTest();
        assertEquals(0, payments.prepares.get());
        wall.reconcileForTest();
        assertEquals(1, payments.reconciles.get());
    }

    @Test void changedSiweIdentityAfterPreparePreventsSigning() {
        FakePayments payments = new FakePayments();
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<String> identity = new AtomicReference<>(ADDRESS_A);
        TestWallet wallet = new TestWallet(ADDRESS_A, "0x7a69", new CompletableFuture<>());
        X402Paywall wall = create(payments, wallet, identity::get, executor);

        wall.payNowForTest();
        identity.set(ADDRESS_B);
        executor.runNext();

        assertEquals(0, wallet.signatures.get());
        assertEquals(0, payments.settlements.get());
        assertEquals(PaywallState.ERROR, wall.getState());
    }

    @Test void changedWalletAccountOrChainAfterPreparePreventsSigning() {
        for (boolean changeAccount : List.of(true, false)) {
            FakePayments payments = new FakePayments();
            ManualExecutor executor = new ManualExecutor();
            TestWallet wallet = new TestWallet(ADDRESS_A, "0x7a69", new CompletableFuture<>());
            X402Paywall wall = create(payments, wallet, () -> ADDRESS_A, executor);

            wall.payNowForTest();
            if (changeAccount) {
                wallet.account = ADDRESS_B;
            } else {
                wallet.chain = "0x1";
            }
            executor.runNext();

            assertEquals(0, wallet.signatures.get());
            assertEquals(0, payments.settlements.get());
            assertEquals(PaywallState.ERROR, wall.getState());
        }
    }

    @Test void signedPaymentIsNotSettledAfterSiweLogout() {
        FakePayments payments = new FakePayments();
        ManualExecutor executor = new ManualExecutor();
        AtomicReference<String> identity = new AtomicReference<>(ADDRESS_A);
        CompletableFuture<String> signature = new CompletableFuture<>();
        TestWallet wallet = new TestWallet(ADDRESS_A, "0x7a69", signature);
        X402Paywall wall = create(payments, wallet, identity::get, executor);

        wall.payNowForTest();
        executor.runNext();
        signature.complete("sig");
        identity.set(null);
        executor.runNext();

        assertEquals(0, payments.settlements.get());
        assertEquals(PaywallState.ERROR, wall.getState());
        assertTrue(wall.getErrorMessageForTest().contains("Sign in with SIWE"));
    }

    private X402Paywall create(FakePayments payments, TestWallet wallet) { return create(payments, wallet, () -> ADDRESS_A); }

    private X402Paywall create(FakePayments payments, TestWallet wallet, Supplier<String> identity) {
        return create(payments, wallet, identity, Runnable::run);
    }

    private X402Paywall create(FakePayments payments, TestWallet wallet, Supplier<String> identity, Executor executor) {
        ui = new TestUI(); UI.setCurrent(ui);
        X402Paywall wall = new X402Paywall(payments, "demo-article",
                new X402Resource("http://localhost/paid-article", "Article", "text/html"), "1000000", 6,
                "eip155:31337", "0x0000000000000000000000000000000000000001", executor, identity);
        wall.setWallet(wallet);
        ui.add(wall);
        if (executor instanceof ManualExecutor manualExecutor) {
            manualExecutor.runAll();
        }
        return wall;
    }

    private static PaymentAttempt attempt() {
        var requirements = new PaymentRequirements("exact", "eip155:31337", "1000000",
                "0x0000000000000000000000000000000000000001", ADDRESS_A, 300, java.util.Map.of());
        return new PaymentAttempt("id", requirements, "{}",
                new TransferAuthorization(ADDRESS_A, ADDRESS_A, "1000000", "1", "9999999999", "0x01"), Instant.now());
    }

    private static class FakePayments implements X402PaymentService {
        final AtomicInteger prepares = new AtomicInteger(), settlements = new AtomicInteger(), reconciles = new AtomicInteger();
        PaymentOutcome settle = new PaymentOutcome("id", PaymentStatus.SETTLED, null, null);
        PaymentOutcome reconciled = settle;
        PaymentOutcome latest = settle;
        PaymentStatus accessStatus = PaymentStatus.SETTLED;
        @Override public PaymentAttempt prepare(String resource, String address) { prepares.incrementAndGet(); return attempt(); }
        @Override public PaymentOutcome verifyAndSettle(String resource, PaymentPayload payload) { settlements.incrementAndGet(); accessStatus = settle.status(); return settle; }
        @Override public PaymentOutcome reconcile(String id) { reconciles.incrementAndGet(); accessStatus = reconciled.status(); return reconciled; }
        @Override public AccessDecision hasAccess(String resource, String address) { return accessStatus == PaymentStatus.SETTLED ? AccessDecision.ALLOW : AccessDecision.PAYMENT_PENDING; }
        @Override public PaymentRequired createChallenge(String resource, URI uri) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<PaymentOutcome> latestOutcome(String resource, String address) {
            return java.util.Optional.ofNullable(latest);
        }
    }

    private static final class TestWallet extends Web3Connect {
        private String account, chain;
        private final CompletableFuture<String> signature;
        final AtomicInteger signatures = new AtomicInteger();
        TestWallet(String account, String chain, CompletableFuture<String> signature) { super(true); this.account = account; this.chain = chain; this.signature = signature; }
        @Override public boolean isConnected() { return true; }
        @Override public String getAccount() { return account; }
        @Override public String getChainId() { return chain; }
        @Override public CompletableFuture<String> signTypedData(String typedData) { signatures.incrementAndGet(); return signature; }
    }

    private static final class ManualExecutor implements Executor {
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        void runNext() {
            Runnable task = tasks.poll();
            if (task == null) {
                throw new AssertionError("No queued task");
            }
            task.run();
        }
        void runAll() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }

    private static final class TestUI extends UI {
        String navigatedTo;
        @Override public Future<Void> access(Command command) { command.execute(); return CompletableFuture.completedFuture(null); }
        @Override public void navigate(String location) { navigatedTo = location; }
    }
}
