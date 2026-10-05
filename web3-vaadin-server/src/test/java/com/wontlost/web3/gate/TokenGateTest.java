package com.wontlost.web3.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.math.BigInteger;

import com.vaadin.flow.internal.ReflectTools;
import com.vaadin.flow.router.ErrorParameter;

import org.junit.jupiter.api.Test;

class TokenGateTest {
    @Test void rerouteExceptionsHavePublicNoArgConstructors() {
        assertDoesNotThrow(() -> ReflectTools.createInstance(TokenGateDeniedException.class));
        assertDoesNotThrow(() -> ReflectTools.createInstance(TokenGateUnavailableException.class));
    }

    @Test void defaultErrorViewsSetStatusAndRenderMessages() {
        TokenGateDeniedView denied = new TokenGateDeniedView();
        assertEquals(403, denied.setErrorParameter(null,
                new ErrorParameter<>(TokenGateDeniedException.class, new TokenGateDeniedException(), "2 USDC")));
        assertEquals("Access requires 2 USDC", denied.getChildren()
                .filter(com.vaadin.flow.component.html.Paragraph.class::isInstance)
                .map(com.vaadin.flow.component.html.Paragraph.class::cast).findFirst().orElseThrow()
                .getElement().getText());

        TokenGateUnavailableView unavailable = new TokenGateUnavailableView();
        assertEquals(503, unavailable.setErrorParameter(null,
                new ErrorParameter<>(TokenGateUnavailableException.class,
                        new TokenGateUnavailableException(), "try later")));
        assertEquals("Please try again later.", unavailable.getChildren()
                .filter(com.vaadin.flow.component.html.Paragraph.class::isInstance)
                .map(com.vaadin.flow.component.html.Paragraph.class::cast).findFirst().orElseThrow()
                .getElement().getText());
    }

    @Test void rpcFailureSelectsUnavailableAndLowBalanceSelectsDenied() {
        assertEquals(TokenGate.Decision.UNAVAILABLE,
                TokenGate.checkBalance(() -> { throw new IllegalStateException("RPC failed"); }, BigInteger.ONE));
        assertEquals(TokenGate.Decision.INSUFFICIENT,
                TokenGate.checkBalance(() -> BigInteger.ZERO, BigInteger.ONE));
    }

    @Test void unavailableExceptionExplainsTemporaryVerificationFailure() {
        assertEquals("Token balance cannot be verified temporarily",
                new TokenGateUnavailableException("Token balance cannot be verified temporarily").getMessage());
    }
    @Test void unauthenticatedAnnotatedViewRequiresSignIn() {
        assertEquals(TokenGate.Decision.SIGN_IN_REQUIRED,
                TokenGate.decide(true, false, BigInteger.ZERO, BigInteger.ONE));
    }
    @Test void sufficientBalanceIsAllowed() {
        assertEquals(TokenGate.Decision.ALLOW,
                TokenGate.decide(true, true, BigInteger.TEN, BigInteger.ONE));
    }
    @Test void insufficientBalanceIsDenied() {
        assertEquals(TokenGate.Decision.INSUFFICIENT,
                TokenGate.decide(true, true, BigInteger.ZERO, BigInteger.ONE));
    }
    @Test void unannotatedViewIsUnaffected() {
        assertEquals(TokenGate.Decision.ALLOW,
                TokenGate.decide(false, false, BigInteger.ZERO, BigInteger.ONE));
    }

    @RequiresToken(chainId = 11155111, token = "USDC", minBalance = "1", decimals = 6)
    private static final class UsdcGatedView { }

    @RequiresToken(chainId = 1, token = "USDT", minBalance = "1", decimals = 6)
    private static final class UsdtGatedView { }

    @RequiresToken(chainId = 11155111, token = "pyusd", minBalance = "1", decimals = 6)
    private static final class PyusdGatedView { }

    @Test void builtInTokenSymbolsResolveAndMissingSymbolChainFailsClosed() {
        com.wontlost.web3.chain.ChainRegistry registry = new com.wontlost.web3.chain.ChainRegistry();
        registry.register(1, new com.wontlost.web3.chain.EthRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x0\"}"));
        registry.register(11155111, new com.wontlost.web3.chain.EthRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x0\"}"));
        TokenGate gate = new TokenGate(registry);
        assertEquals(TokenGate.Decision.INSUFFICIENT, gate.evaluate(UsdtGatedView.class.getAnnotation(RequiresToken.class),
                "0x0000000000000000000000000000000000000001"));
        assertEquals(TokenGate.Decision.INSUFFICIENT, gate.evaluate(PyusdGatedView.class.getAnnotation(RequiresToken.class),
                "0x0000000000000000000000000000000000000001"));
        registry.register(10, new com.wontlost.web3.chain.EthRpcClient(request -> "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x0\"}"));
        RequiresToken missing = new RequiresToken() {
            public long chainId() { return 10; }
            public String token() { return "EURC"; }
            public String minBalance() { return "1"; }
            public int decimals() { return 6; }
            public String redirectTo() { return "login"; }
            public Class<? extends java.lang.annotation.Annotation> annotationType() { return RequiresToken.class; }
        };
        assertEquals(TokenGate.Decision.UNAVAILABLE, gate.evaluate(missing, "0x0000000000000000000000000000000000000001"));
    }

    @Test void missingChainRegistryFailsClosed() {
        // 应用未注册 ChainRegistry 时监听器装入空注册表：带注解的视图必须被拒绝，而不是放行
        RequiresToken requirement = UsdcGatedView.class.getAnnotation(RequiresToken.class);
        org.junit.jupiter.api.Assertions.assertEquals(TokenGate.Decision.UNAVAILABLE,
                new TokenGate(new com.wontlost.web3.chain.ChainRegistry())
                        .evaluate(requirement, "0x0000000000000000000000000000000000000001"));
    }
}
