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
}
