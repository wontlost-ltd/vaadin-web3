package com.wontlost.web3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wontlost.web3.pay.PaymentRequest;
import com.wontlost.web3.pay.PaymentStatus;
import com.wontlost.web3.siwe.SiweExpectations;
import com.wontlost.web3.siwe.SiweException;
import com.wontlost.web3.siwe.SiweVerifier;

class TestKitVerifierTest {
    private static final String DOMAIN = "example.test";
    private static final String URI = "https://example.test/login";

    @Test
    void verifiesSignedSiweAndConsumesNonce() {
        TestNonceStore nonces = new TestNonceStore();
        SiweTestSupport support = new SiweTestSupport(DOMAIN, URI, 31337, nonces, TestWallet.anvil(0));
        SiweTestSupport.SignedMessage signed = support.valid();
        var verified = new SiweVerifier(nonces, java.time.Clock.systemUTC()).verify(
                signed.message(), signed.signature(), expectations());
        assertEquals(support.wallet().address(), verified.address());
        assertThrows(SiweException.class, () -> new SiweVerifier(nonces, java.time.Clock.systemUTC())
                .verify(signed.message(), signed.signature(), expectations()));
    }

    @Test
    void controlsNonceExpiryAndRejectsDuplicateIssue() {
        TestNonceStore nonces = new TestNonceStore();
        String nonce = nonces.issue("testNonce123");
        assertThrows(IllegalStateException.class, () -> nonces.issue(nonce));
        nonces.expire(nonce);
        assertFalse(nonces.isActive(nonce));
        assertFalse(nonces.consume(nonce));
    }

    @Test
    void rejectsExpiredAndWrongDomainSiweFixtures() {
        TestNonceStore expiredNonces = new TestNonceStore();
        SiweTestSupport expiredSupport = new SiweTestSupport(DOMAIN, URI, 31337, expiredNonces, TestWallet.anvil(0));
        SiweTestSupport.SignedMessage expired = expiredSupport.expired();
        assertThrows(SiweException.class, () -> new SiweVerifier(expiredNonces, java.time.Clock.systemUTC())
                .verify(expired.message(), expired.signature(), expectations()));

        TestNonceStore domainNonces = new TestNonceStore();
        SiweTestSupport domainSupport = new SiweTestSupport(DOMAIN, URI, 31337, domainNonces, TestWallet.anvil(1));
        SiweTestSupport.SignedMessage wrongDomain = domainSupport.wrongDomain("other.test");
        assertThrows(SiweException.class, () -> new SiweVerifier(domainNonces, java.time.Clock.systemUTC())
                .verify(wrongDomain.message(), wrongDomain.signature(), expectations()));
    }

    @Test
    void verifiesPaymentConfirmedConfirmingUnderpaidAndReorg() {
        String txHash = "0x" + "11".repeat(32);
        String token = "0x" + "22".repeat(20);
        String from = "0x" + "33".repeat(20);
        String to = "0x" + "44".repeat(20);
        String blockHash = "0x" + "55".repeat(32);
        BigInteger amount = BigInteger.valueOf(100);
        PaymentTestSupport.ConfiguredPayment payment = PaymentTestSupport.rpc(txHash, token, from, to, amount,
                100, 105, blockHash, blockHash);
        PaymentRequest exact = new PaymentRequest(1, token, to, amount, from);
        assertEquals(PaymentStatus.CONFIRMED, payment.verifier().verify("confirmed", txHash, exact).status());
        assertEquals(PaymentStatus.CONFIRMING, payment.verifier().verify("confirming", txHash,
                exact.withFinality(com.wontlost.web3.pay.Finality.confirmations(10))).status());
        assertEquals(PaymentStatus.UNDERPAID, payment.verifier().verify("underpaid", txHash,
                new PaymentRequest(1, token, to, amount.add(BigInteger.ONE), from)).status());
        payment.canonicalHash("0x" + "66".repeat(32));
        assertEquals(PaymentStatus.PENDING, payment.verifier().verify("reorg", txHash, exact).status());
    }

    private static SiweExpectations expectations() {
        return new SiweExpectations(DOMAIN, URI, Set.of(31337L), null);
    }
}
