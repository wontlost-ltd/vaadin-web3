package com.wontlost.web3.pay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.chain.ChainRegistry;

class StablecoinCheckoutTest {
    private static final String RECIPIENT = "0x0000000000000000000000000000000000000001";

    @Test void defaultsToUsdcAndSupportsValidatedTokenSelection() {
        StablecoinCheckout checkout = new StablecoinCheckout(new ChainRegistry(), new InMemoryPaymentLedger(),
                RECIPIENT, new BigDecimal("25.00"));
        assertEquals("Pay 25.00 USDC", button(checkout).getText());
        assertFalse(tokenSelect(checkout).isVisible());
        assertThrows(IllegalArgumentException.class, () -> checkout.setTokens());
        assertThrows(IllegalArgumentException.class, () -> checkout.setTokens("bogus"));
        assertThrows(IllegalArgumentException.class, () -> checkout.setTokens("USDC", "EURC"));
        assertEquals(checkout, checkout.setTokens("usdt", "PYUSD"));
        assertEquals(java.util.List.of("USDT", "PYUSD"), checkout.getTokens());
        assertEquals("Pay 25.00 USDT", button(checkout).getText());
        tokenSelect(checkout).setValue("PYUSD");
        assertEquals("Pay 25.00 PYUSD", button(checkout).getText());
        checkout.setButtonText("Continue");
        assertEquals("Continue", button(checkout).getText());
    }

    @Test void tokenSelectionRecomputesNetworksAndSingleTokenHidesSelector() {
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        registry.register(43114, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        registry.register(8453, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        StablecoinCheckout checkout = new StablecoinCheckout(registry, new InMemoryPaymentLedger(), RECIPIENT, BigDecimal.ONE)
                .setTokens("USDC", "USDT");
        assertEquals(2, checkout.getChildren().filter(com.vaadin.flow.component.select.Select.class::isInstance).count());
        var network = networkSelect(checkout);
        assertEquals(java.util.Set.of(1L, 43114L, 8453L), network.getListDataView().getItems()
                .map(item -> (Long) item).collect(java.util.stream.Collectors.toSet()));
        tokenSelect(checkout).setValue("USDT");
        assertEquals(1L, network.getValue());
        assertEquals(java.util.Set.of(1L, 43114L), network.getListDataView().getItems()
                .map(item -> (Long) item).collect(java.util.stream.Collectors.toSet()));
        checkout.setTokens("EURC");
        assertEquals(java.util.Set.of(1L, 43114L, 8453L), network.getListDataView().getItems()
                .map(item -> (Long) item).collect(java.util.stream.Collectors.toSet()));
        assertFalse(tokenSelect(checkout).isVisible());
    }

    @Test void paymentIntentKeepsTheTokenAndNetworkSelectedAtStart() {
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        registry.register(43114, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        StablecoinCheckout checkout = new StablecoinCheckout(registry, new InMemoryPaymentLedger(), RECIPIENT,
                new BigDecimal("2.50")).setTokens("USDC", "USDT");
        tokenSelect(checkout).setValue("USDT");
        var network = networkSelect(checkout);
        network.setValue(1L);
        StablecoinCheckout.PaymentIntent intent = checkout.capturePaymentIntent();

        tokenSelect(checkout).setValue("USDC");
        network.setValue(43114L);

        assertEquals("USDT", intent.tokenSymbol());
        assertEquals(1L, intent.selectedChain());
        assertEquals(new BigInteger("2500000"), intent.amountUnits());
    }

    @Test void onrampActionRebuildsForTokenAndNetworkAndHidesWhenLocked() {
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        registry.register(8453, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        StablecoinCheckout checkout = new StablecoinCheckout(registry, new InMemoryPaymentLedger(), RECIPIENT,
                new BigDecimal("2.50")).setTokens("USDC", "USDT");
        java.util.List<String> rebuilt = new java.util.ArrayList<>();
        checkout.setOnrampAction((source, token, amount) -> {
            rebuilt.add(token.symbol() + "@" + token.chainId() + ":" + amount.toPlainString());
            return new com.vaadin.flow.component.html.Span(token.symbol() + " on " + token.chainId());
        });
        assertTrue(rebuilt.contains("USDC@1:2.50"));
        tokenSelect(checkout).setValue("USDT");
        assertTrue(rebuilt.contains("USDT@1:2.50"));
        tokenSelect(checkout).setValue("USDC");
        networkSelect(checkout).setValue(8453L);
        assertTrue(rebuilt.contains("USDC@8453:2.50"));
        assertTrue(checkout.getChildren().anyMatch(c -> c instanceof com.vaadin.flow.component.html.Span span
                && span.getText().equals("USDC on 8453")));
        checkout.applyVerificationResult(new PaymentResult(PaymentStatus.CONFIRMED, "0xabc", null, BigInteger.ONE, 1));
        var action = checkout.getChildren().filter(com.vaadin.flow.component.html.Span.class::isInstance)
                .map(com.vaadin.flow.component.html.Span.class::cast).filter(span -> span.getText().contains(" on ")).findFirst().orElseThrow();
        assertFalse(action.isVisible());
    }

    @Test void onrampActionCanReturnNullAndCheckoutExposesCurrentContext() {
        ChainRegistry registry = new ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        StablecoinCheckout checkout = new StablecoinCheckout(registry, new InMemoryPaymentLedger(), RECIPIENT, BigDecimal.ONE)
                .setOrderId("order-42").setOnrampAction((source, token, amount) -> null);
        assertEquals("order-42", checkout.getOrderId());
        assertEquals(null, checkout.getConnectedAccount());
        assertFalse(checkout.getChildren().anyMatch(c -> c instanceof com.vaadin.flow.component.html.Span span
                && span.getText().contains(" on ")));
    }

    private static com.vaadin.flow.component.button.Button button(StablecoinCheckout checkout) {
        return checkout.getChildren().filter(com.vaadin.flow.component.button.Button.class::isInstance)
                .map(com.vaadin.flow.component.button.Button.class::cast).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static com.vaadin.flow.component.select.Select<Long> networkSelect(StablecoinCheckout checkout) {
        return checkout.getChildren().filter(com.vaadin.flow.component.select.Select.class::isInstance)
                .map(com.vaadin.flow.component.select.Select.class::cast)
                .filter(select -> "Network".equals(select.getLabel()))
                .map(select -> (com.vaadin.flow.component.select.Select<Long>) select)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static com.vaadin.flow.component.select.Select<String> tokenSelect(StablecoinCheckout checkout) {
        return checkout.getChildren().filter(com.vaadin.flow.component.select.Select.class::isInstance)
                .map(com.vaadin.flow.component.select.Select.class::cast)
                .filter(select -> "Token".equals(select.getLabel()))
                .map(select -> (com.vaadin.flow.component.select.Select<String>) select)
                .findFirst().orElseThrow();
    }

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

    @Test void paymentEventsExposeTokenAndKeepLegacyConstructors() {
        StablecoinCheckout checkout = new StablecoinCheckout(new ChainRegistry(), new InMemoryPaymentLedger(),
                RECIPIENT, BigDecimal.ONE);
        var token = com.wontlost.web3.chain.Tokens.usdt(1).orElseThrow();
        assertEquals(token, new StablecoinCheckout.PaymentSubmittedEvent(checkout, "0xhash", token).getToken());
        assertEquals(token, new StablecoinCheckout.PaymentConfirmedEvent(checkout,
                new PaymentResult(PaymentStatus.CONFIRMED, "0xhash", null, BigInteger.ONE, 1), token).getToken());
        assertEquals(token, new StablecoinCheckout.PaymentFailedEvent(checkout,
                new PaymentResult(PaymentStatus.FAILED, "0xhash", null, BigInteger.ZERO, 0), false, token).getToken());
        assertEquals(null, new StablecoinCheckout.PaymentSubmittedEvent(checkout, "0xhash").getToken());
        assertEquals(null, new StablecoinCheckout.PaymentConfirmedEvent(checkout,
                new PaymentResult(PaymentStatus.CONFIRMED, "0xhash", null, BigInteger.ONE, 1)).getToken());
        assertEquals(null, new StablecoinCheckout.PaymentFailedEvent(checkout,
                new PaymentResult(PaymentStatus.FAILED, "0xhash", null, BigInteger.ZERO, 0), false).getToken());

        java.util.concurrent.atomic.AtomicReference<StablecoinCheckout.PaymentConfirmedEvent> fired =
                new java.util.concurrent.atomic.AtomicReference<>();
        checkout.addPaymentConfirmedListener(fired::set);
        checkout.setSubmittedTransactionForTest("0xhash", "order-usdt", new PaymentRequest(1,
                token.address(), RECIPIENT, BigInteger.ONE));
        checkout.applyVerificationResult(new PaymentResult(PaymentStatus.CONFIRMED, "0xhash", null,
                BigInteger.ONE, 1));
        assertEquals(token, fired.get().getToken());
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
        org.junit.jupiter.api.Assertions.assertEquals("Avalanche Fuji", StablecoinCheckout.networkName(43113L));
        for (String symbol : com.wontlost.web3.chain.Tokens.symbols()) {
            for (long id : com.wontlost.web3.chain.Tokens.chains(symbol)) {
            assertFalse(StablecoinCheckout.networkName(id).startsWith("Chain "), "unnamed chain " + id);
            }
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

    @Test void paidCheckoutLocksTokenAndNetworkUntilReset() {
        StablecoinCheckout checkout = new StablecoinCheckout(new ChainRegistry(), new InMemoryPaymentLedger(),
                RECIPIENT, BigDecimal.ONE).setTokens("USDC", "PYUSD");

        checkout.applyVerificationResult(new PaymentResult(PaymentStatus.CONFIRMED, "0xabc", null, BigInteger.ONE, 1));

        // 已付款后不能再切换代币或网络，按钮文案保持 "Paid"
        assertFalse(tokenSelect(checkout).isEnabled());
        assertFalse(networkSelect(checkout).isEnabled());
        assertEquals("Paid", button(checkout).getText());
        // 锁定期间的配置修改会让界面与已捕获的支付不一致，必须先 reset
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> checkout.setTokens("PYUSD"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> checkout.setPreferredChain(1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> checkout.setAllowedChainIds(java.util.Set.of(1L)));
        checkout.reset("order-2");
        checkout.setTokens("PYUSD");
        assertTrue(tokenSelect(checkout).isEnabled());
        assertTrue(networkSelect(checkout).isEnabled());
    }

    @Test void paymentIntentOnlyAcceptsTransactionsMinedAfterPayWasClicked() {
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        StablecoinCheckout checkout = new StablecoinCheckout(chains, new InMemoryPaymentLedger(), RECIPIENT, BigDecimal.ONE);
        java.time.Instant before = java.time.Instant.now();
        java.time.Instant notBefore = checkout.capturePaymentIntent().notBefore();
        // 只放宽时钟误差容忍，不能更早（否则旧交易可冒充本次付款）
        assertTrue(!notBefore.isBefore(before.minus(StablecoinCheckout.NOT_BEFORE_TOLERANCE).minusSeconds(1)));
        assertTrue(!notBefore.isAfter(java.time.Instant.now().minus(StablecoinCheckout.NOT_BEFORE_TOLERANCE)));
        checkout.setTransactionTimeTolerance(java.time.Duration.ZERO);
        assertTrue(!checkout.capturePaymentIntent().notBefore().isBefore(before));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> checkout.setTransactionTimeTolerance(java.time.Duration.ofSeconds(-1)));
    }
}
