package com.wontlost.web3.pay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.ChainRegistry;

class StablecoinCheckoutTest {
    @Test void confirmationDisablesPayUntilResetForANewOrder() {
        StablecoinCheckout checkout = new StablecoinCheckout(new ChainRegistry(), new InMemoryPaymentLedger(),
                "0x0000000000000000000000000000000000000001", BigDecimal.ONE);

        checkout.applyVerificationResult(new PaymentResult(PaymentStatus.CONFIRMED, "0xabc", null,
                BigInteger.ONE, 1));

        assertTrue(checkout.isPaid());
        assertFalse(checkout.getChildren().filter(com.vaadin.flow.component.button.Button.class::isInstance)
                .map(com.vaadin.flow.component.button.Button.class::cast).findFirst().orElseThrow().isEnabled());
        checkout.reset("order-2");
        assertFalse(checkout.isPaid());
        assertTrue(checkout.getChildren().filter(com.vaadin.flow.component.button.Button.class::isInstance)
                .map(com.vaadin.flow.component.button.Button.class::cast).findFirst().orElseThrow().isEnabled());
    }

    @Test void saturatedVerifierPoolRetriesOnTheNextPoll() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.Executor saturated = task -> {
            attempts.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("pool full");
        };
        StablecoinCheckout checkout = new StablecoinCheckout(new ChainRegistry(), new InMemoryPaymentLedger(),
                "0x0000000000000000000000000000000000000001", BigDecimal.ONE, saturated);
        checkout.setSubmittedTransactionForTest("0xabc", "order-1", new PaymentRequest(11155111,
                "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238", "0x0000000000000000000000000000000000000001",
                BigInteger.ONE));

        checkout.verifyPayment();
        checkout.verifyPayment();

        // 线程池拒绝后标志位必须复位，下一次轮询才会再次提交校验
        org.junit.jupiter.api.Assertions.assertEquals(2, attempts.get());
        assertFalse(checkout.isPaid());
    }

    @Test void networksAreShownByName() {
        org.junit.jupiter.api.Assertions.assertEquals("Sepolia", StablecoinCheckout.networkName(11155111L));
        org.junit.jupiter.api.Assertions.assertEquals("Base", StablecoinCheckout.networkName(8453L));
        org.junit.jupiter.api.Assertions.assertEquals("Chain 999", StablecoinCheckout.networkName(999L));
        // 每个内置 USDC 链都必须有名字，避免回退显示原始 id
        for (long id : new long[] {1, 11155111, 8453, 84532, 42161, 421614, 10, 11155420, 137, 80002, 43114}) {
            org.junit.jupiter.api.Assertions.assertTrue(com.wontlost.web3.chain.Tokens.usdc(id).isPresent());
            assertFalse(StablecoinCheckout.networkName(id).startsWith("Chain "), "unnamed chain " + id);
        }
    }

    @Test void verificationStartedBeforeResetDoesNotPayTheNewOrder() throws Exception {
        var fixture = new tools.jackson.databind.ObjectMapper().readTree(
                getClass().getResourceAsStream("/fixtures/usdc-mainnet.json").readAllBytes());
        var receipt = fixture.path("simpleTransfer").path("receipt");
        var transfer = receipt.path("logs").get(0);
        String recipient = "0x" + transfer.path("topics").get(2).asString().substring(26);
        BigInteger amount = new BigInteger(transfer.path("data").asString().substring(2), 16);
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(
                new com.wontlost.web3.chain.OnChainSupportTest.FixtureTransport(fixture)));
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        StablecoinCheckout checkout = new StablecoinCheckout(chains, new InMemoryPaymentLedger(), recipient,
                BigDecimal.ONE, queued::add);
        // 无会话的 UI：access 直接同步执行，模拟拿到会话锁后的回调
        com.vaadin.flow.component.UI ui = new com.vaadin.flow.component.UI() {
            @Override public java.util.concurrent.Future<Void> access(com.vaadin.flow.server.Command command) {
                command.execute();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        ui.add(checkout);
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            checkout.setSubmittedTransactionForTest(receipt.path("transactionHash").asString(), "order-1",
                    new PaymentRequest(1, com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(), recipient,
                            amount));
            checkout.startPolling();
            checkout.verifyPayment();
            checkout.reset("order-2");
            checkout.startPolling();
            checkout.setSubmittedTransactionForTest("0xb0", "order-2", new PaymentRequest(1,
                    com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(), recipient, amount));
            checkout.verifyPayment();
            // 订单 1 的校验尚未完成，也不能阻塞订单 2 的校验
            org.junit.jupiter.api.Assertions.assertEquals(2, queued.size());

            // 订单 1 的校验在 reset 之后才完成，且结果为 CONFIRMED
            queued.get(0).run();

            assertFalse(checkout.isPaid(), "order-1 confirmation must not mark order-2 as paid");
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }
}
