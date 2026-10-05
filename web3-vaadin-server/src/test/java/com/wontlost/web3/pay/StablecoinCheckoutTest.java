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

    @Test void screeningBlockFiresFailureDoesNotSubmitAndRestoresPay() {
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        // 排队执行器：筛查在后台执行，便于断言"调用返回时尚未阻塞等待结果"
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        StablecoinCheckout checkout = new StablecoinCheckout(chains, new InMemoryPaymentLedger(), RECIPIENT, BigDecimal.ONE, queued::add);
        com.vaadin.flow.server.VaadinContext context = (com.vaadin.flow.server.VaadinContext)
                java.lang.reflect.Proxy.newProxyInstance(com.vaadin.flow.server.VaadinContext.class.getClassLoader(),
                        new Class<?>[] { com.vaadin.flow.server.VaadinContext.class }, (proxy, method, args) -> {
                            if ("setAttribute".equals(method.getName())) { screeningContext.put((Class<?>) args[0], args[1]); return null; }
                            if ("getAttribute".equals(method.getName())) {
                                Object value = screeningContext.get(args[0]);
                                return value == null && args.length == 2 ? ((java.util.function.Supplier<?>) args[1]).get() : value;
                            }
                            return null;
                        });
        com.wontlost.web3.screening.AddressScreening.register(context, address ->
                com.wontlost.web3.screening.AddressScreening.ScreeningDecision.block("sanctions"));
        checkout.setScreeningContextLookup(() -> context);
        java.util.concurrent.atomic.AtomicReference<StablecoinCheckout.PaymentFailedEvent> event = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<StablecoinCheckout.PaymentSubmittedEvent> submitted = new java.util.concurrent.atomic.AtomicReference<>();
        checkout.addPaymentFailedListener(event::set);
        checkout.addPaymentSubmittedListener(submitted::set);
        var wallet = checkout.getChildren().filter(com.wontlost.web3.Web3Connect.class::isInstance)
                .map(com.wontlost.web3.Web3Connect.class::cast).findFirst().orElseThrow();
        wallet.getElement().setProperty("chainId", "0x1");
        checkout.onWalletConnected(checkout.capturePaymentIntent(), "order-1", null, null, 0,
                "0x0000000000000000000000000000000000000002", null);
        // 筛查不在调用线程同步执行：任务入队、此刻还没有结果
        assertEquals(1, queued.size());
        assertEquals(null, event.get());
        queued.removeFirst().run();
        assertEquals(PaymentStatus.FAILED, event.get().getResult().status());
        assertEquals("sanctions", event.get().getBlockedReason());
        assertTrue(button(checkout).isEnabled());
        assertEquals(null, readField(checkout, "transactionHash"));
        assertEquals(null, submitted.get());
        assertTrue(readField(checkout, "submittedRequest") != null);
        screeningContext.clear();
        com.wontlost.web3.screening.AddressScreening.register(context, address -> { throw new IllegalStateException("offline"); });
        checkout.onWalletConnected(checkout.capturePaymentIntent(), "order-2", null, null, 0,
                "0x0000000000000000000000000000000000000002", null);
        queued.removeFirst().run();
        assertEquals(PaymentStatus.FAILED, event.get().getResult().status());
        assertEquals("SCREENING_UNAVAILABLE", event.get().getBlockedReason());
        assertTrue(button(checkout).isEnabled());
        screeningContext.clear();
    }
    private static final java.util.Map<Class<?>, Object> screeningContext = new java.util.HashMap<>();
    private static Object readField(Object target, String name) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

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

    @Test void localizedPayAndAsyncPaymentStatusUseConfiguredMessages() {
        StablecoinCheckout checkout = new StablecoinCheckout(new ChainRegistry(), new InMemoryPaymentLedger(),
                RECIPIENT, new BigDecimal("25.00"));
        checkout.setI18n(new StablecoinCheckoutI18n().setPay("Payer {0} {1}")
                .setConfirmed("État {0}, confirmations {1}").setPaid("Réglé"));
        assertEquals("Payer 25.00 USDC", button(checkout).getText());
        checkout.applyVerificationResult(new PaymentResult(PaymentStatus.CONFIRMED, "0xhash", RECIPIENT,
                BigInteger.ONE, 4));
        assertEquals("État CONFIRMED, confirmations 4", checkout.getChildren()
                .filter(com.vaadin.flow.component.html.Span.class::isInstance)
                .map(com.vaadin.flow.component.html.Span.class::cast).skip(1).findFirst().orElseThrow().getText());
        assertEquals("Réglé", button(checkout).getText());
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

    private static com.vaadin.flow.component.html.Span status(StablecoinCheckout checkout) {
        return checkout.getChildren().filter(com.vaadin.flow.component.html.Span.class::isInstance)
                .map(com.vaadin.flow.component.html.Span.class::cast)
                .filter(span -> span.getText().startsWith("Transaction sent") || span.getText().startsWith("CONFIRMING"))
                .findFirst().orElseThrow();
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

    @Test void hostedModeWithoutRegisteredClientFailsBeforeWalletInteraction() throws Exception {
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        StablecoinCheckout checkout = new StablecoinCheckout(chains, new InMemoryPaymentLedger(), RECIPIENT, BigDecimal.ONE)
                .setPaymentMonitor(true);
        java.util.concurrent.atomic.AtomicReference<StablecoinCheckout.PaymentFailedEvent> event = new java.util.concurrent.atomic.AtomicReference<>();
        checkout.addPaymentFailedListener(event::set);
        var begin = StablecoinCheckout.class.getDeclaredMethod("beginPayment");
        begin.setAccessible(true);
        begin.invoke(checkout);
        assertEquals(PaymentStatus.FAILED, event.get().getResult().status());
        assertTrue(event.get().getResult().txHash() == null);
        assertTrue(button(checkout).isEnabled());
    }

    @Test void hostedStatusesMapIntoExistingPaymentResultsAndOnlyIdIsRetained() {
        java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");
        for (com.wontlost.web3.monitor.MonitoredStatus status : com.wontlost.web3.monitor.MonitoredStatus.values()) {
            var payment = new com.wontlost.web3.monitor.MonitoredPayment("payment-id", "order", 1,
                    new com.wontlost.web3.monitor.MonitoredPayment.Token("USDC", RECIPIENT, 6), RECIPIENT,
                    "1", null, 1, status, null, "0", 0, now, now.plusSeconds(3600), now, now);
            PaymentStatus expected = switch (status) {
                case AWAITING_TRANSACTION, PENDING -> PaymentStatus.PENDING;
                case EXPIRED -> PaymentStatus.FAILED;
                default -> PaymentStatus.valueOf(status.name());
            };
            assertEquals(expected, StablecoinCheckout.toPaymentResult(payment).status());
        }
        var idField = java.util.Arrays.stream(StablecoinCheckout.class.getDeclaredFields())
                .filter(field -> field.getName().equals("hostedPaymentId")).findFirst().orElseThrow();
        assertEquals(String.class, idField.getType());
        assertFalse(java.util.Arrays.stream(StablecoinCheckout.class.getDeclaredFields())
                .anyMatch(field -> com.wontlost.web3.monitor.PaymentMonitorClient.class.isAssignableFrom(field.getType())));
    }

    @Test void hostedHashSurvivesSubmitFailureAndNextPollRetriesBeforeReadingPayment() {
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{}"));
        StablecoinCheckout checkout = new StablecoinCheckout(chains, new InMemoryPaymentLedger(), RECIPIENT,
                BigDecimal.ONE, Runnable::run);
        var ui = new com.vaadin.flow.component.UI() {
            @Override public java.util.concurrent.Future<Void> access(com.vaadin.flow.server.Command command) {
                command.execute();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        ui.add(checkout);
        java.util.concurrent.atomic.AtomicInteger submissions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<StablecoinCheckout.PaymentFailedEvent> failed = new java.util.concurrent.atomic.AtomicReference<>();
        checkout.addPaymentFailedListener(failed::set);
        checkout.setHostedPaymentOperationsForTest(new StablecoinCheckout.HostedPaymentOperations() {
            @Override public void submit(String paymentId, String hash) {
                if (submissions.incrementAndGet() == 1) throw new IllegalStateException("temporary monitor outage");
            }
            @Override public com.wontlost.web3.monitor.MonitoredPayment get(String paymentId) {
                reads.incrementAndGet();
                java.time.Instant now = java.time.Instant.now();
                return new com.wontlost.web3.monitor.MonitoredPayment(paymentId, "test-order", 1,
                        new com.wontlost.web3.monitor.MonitoredPayment.Token("USDC", RECIPIENT, 6), RECIPIENT,
                        "1", null, 1, com.wontlost.web3.monitor.MonitoredStatus.CONFIRMING,
                        "0xhash", "0", 1, now, now.plusSeconds(3600), now, now);
            }
        }, "payment-id");
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            checkout.recordHostedTransactionForTest("0xhash", new IllegalStateException("temporary monitor outage"));
            assertFalse(button(checkout).isEnabled());
            assertTrue(status(checkout).getText().startsWith("Transaction sent"));
            assertEquals(null, failed.get());
            checkout.startPolling();
            checkout.verifyPayment();
            assertEquals(2, submissions.get());
            assertEquals(1, reads.get());
            assertEquals(null, failed.get());
            assertFalse(button(checkout).isEnabled());
            assertTrue(status(checkout).getText().startsWith("CONFIRMING"));
        } finally {
            checkout.reset("next-order");
            com.vaadin.flow.component.UI.setCurrent(null);
        }
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

    @Test void networkOutageAfterTheTransactionWasSentNeverReEnablesPay() throws Exception {
        // 交易已发出后校验持续网络失败：不能触发失败事件、不能重新启用 Pay（防止重复付款）
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> { throw new java.io.IOException("offline"); }));
        StablecoinCheckout checkout = new StablecoinCheckout(chains, new InMemoryPaymentLedger(), RECIPIENT, BigDecimal.ONE, queued::add);
        com.vaadin.flow.component.UI ui = new com.vaadin.flow.component.UI() {
            @Override public java.util.concurrent.Future<Void> access(com.vaadin.flow.server.Command command) {
                command.execute();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        };
        ui.add(checkout);
        com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
            checkout.addPaymentFailedListener(event -> failures.incrementAndGet());
            checkout.setSubmittedTransactionForTest("0x" + "ab".repeat(32), "order-1", new PaymentRequest(1,
                    com.wontlost.web3.chain.Tokens.usdc(1).orElseThrow().address(), RECIPIENT, BigInteger.ONE));
            checkout.startPolling();
            button(checkout).setEnabled(false);
            for (int i = 0; i < StablecoinCheckout.NETWORK_WARNING_THRESHOLD + 5; i++) {
                checkout.verifyPayment();
                queued.removeFirst().run();
            }
            assertEquals(0, failures.get());
            assertFalse(button(checkout).isEnabled());
            assertTrue(checkout.getChildren().filter(com.vaadin.flow.component.html.Span.class::isInstance)
                    .map(component -> ((com.vaadin.flow.component.html.Span) component).getText())
                    .anyMatch(text -> text.contains("Don't pay again")));
        } finally {
            com.vaadin.flow.component.UI.setCurrent(null);
        }
    }
}
