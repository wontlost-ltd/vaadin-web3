package com.wontlost.web3.siws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class SiwsVerifierTest {
    private MutableClock clock;
    private SiwsVerifier verifier;
    private Ed25519PrivateKeyParameters key;
    private String address;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        verifier = new SiwsVerifier(new InMemorySiwsChallengeStore(clock), clock);
        key = new Ed25519PrivateKeyParameters(new SecureRandom());
        address = Base58.encode(key.generatePublicKey().getEncoded());
    }

    @Test
    void rfc8032TestVectorVerifiesWithRawKeys() {
        // RFC 8032 §7.1 TEST 1：空消息
        byte[] publicKey = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] signature = HexFormat.of().parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        assertTrue(signer.verifySignature(signature));
    }

    @Test
    void verifiesAWalletSignatureAndExposesTheSolanaAccount() {
        SiwsChallenge challenge = challenge();
        byte[] message = challenge.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);

        VerifiedSolanaSignIn signIn = verifier.verify(message, sign(message), address);

        assertEquals("solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp:" + address, signIn.account().caip10());
        assertEquals(address, signIn.address());
        assertEquals(clock.instant(), signIn.verifiedAt());
        assertEquals("example.com", signIn.message().domain());
        assertTrue(signIn.toString().indexOf(address) < 0);
    }

    @Test
    void rejectsTamperedMessagesWrongKeysAndMalformedInputs() {
        SiwsChallenge challenge = challenge();
        byte[] message = challenge.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);
        byte[] tampered = challenge.toMessage(address).toMessage().replace("Sign in", "Sign over")
                .getBytes(StandardCharsets.UTF_8);
        Ed25519PrivateKeyParameters other = new Ed25519PrivateKeyParameters(new SecureRandom());
        String otherAddress = Base58.encode(other.generatePublicKey().getEncoded());

        assertCode("siws_message_mismatch", () -> verifier.verify(tampered, sign(tampered), address));
        // 签名者与声明的地址不一致：消息按声明地址渲染，签名却来自另一把钥匙
        assertCode("siws_invalid_signature", () -> verifier.verify(message, sign(message, other), address));
        // 消息按其它地址签名时与期望文本不符
        byte[] forOther = challenge.toMessage(otherAddress).toMessage().getBytes(StandardCharsets.UTF_8);
        assertCode("siws_message_mismatch", () -> verifier.verify(forOther, sign(forOther, other), address));
        assertCode("siws_invalid_public_key", () -> verifier.verify(message, sign(message), "not-base58!"));
        assertCode("siws_invalid_public_key", () -> verifier.verify(message, sign(message), "2g"));
        assertCode("siws_invalid_signature", () -> verifier.verify(message, new byte[63], address));
    }

    @Test
    void rejectsExpiredAndNotYetValidChallenges() {
        SiwsChallenge challenge = challenge();
        byte[] message = challenge.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(message);

        clock.set(Instant.parse("2025-12-31T23:58:59Z"));
        assertCode("siws_not_yet_valid", () -> verifier.verify(message, signature, address));
        clock.set(Instant.parse("2026-01-01T00:05:00Z"));
        assertCode("siws_expired", () -> verifier.verify(message, signature, address));
        // 节点时钟偏差：签发前 59 秒内仍可验证
        clock.set(Instant.parse("2025-12-31T23:59:01Z"));
        verifier.verify(message, signature, address);
    }

    @Test
    void nonceIsConsumedOnlyBySuccessfulVerificationAndCannotBeReplayed() {
        SiwsChallenge challenge = challenge();
        byte[] message = challenge.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(message);

        assertCode("siws_invalid_signature", () -> verifier.verify(message, new byte[64], address));
        verifier.verify(message, signature, address);
        // 成功后挑战已被移除：顺序重放找不到挑战
        assertCode("siws_unknown_challenge", () -> verifier.verify(message, signature, address));
    }

    @Test
    void concurrentReplaysOfOneSignatureSucceedExactlyOnce() throws Exception {
        SiwsChallenge challenge = challenge();
        byte[] message = challenge.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);
        byte[] signature = sign(message);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        Runnable attempt = () -> {
            try {
                start.await();
                verifier.verify(message, signature, address);
                successes.incrementAndGet();
            } catch (SiwsException exception) {
                rejected.incrementAndGet();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        List<Thread> threads = java.util.stream.IntStream.range(0, 8).mapToObj(index -> new Thread(attempt)).toList();
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, successes.get());
        assertEquals(7, rejected.get());
    }

    @Test
    void aSignatureForAnotherDomainWithOurNonceIsRejected() {
        // 钓鱼站点从我们处取得 nonce，让受害者为 evil.com 签名后提交：服务端只信任自己保存的挑战，域名不符即拒绝
        SiwsChallenge ours = challenge();
        SiwsChallenge phishing = new SiwsChallenge("evil.com", ours.statement(), "https://evil.com/login",
                ours.cluster(), ours.nonce(), ours.issuedAt(), ours.expirationTime(), ours.resources());
        byte[] phished = phishing.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);

        assertCode("siws_message_mismatch", () -> verifier.verify(phished, sign(phished), address));
        // 未被消费：合法签名仍可完成登录
        byte[] genuine = ours.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);
        verifier.verify(genuine, sign(genuine), address);
    }

    @Test
    void unknownNoncesAndMalformedMessagesAreRejected() {
        SiwsChallenge never = new SiwsChallenge("example.com", null, null, SolanaCluster.DEVNET, "deadbeef",
                "2026-01-01T00:00:00Z", "2026-01-01T00:05:00Z", List.of());
        byte[] unknown = never.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);

        assertCode("siws_unknown_challenge", () -> verifier.verify(unknown, sign(unknown), address));
        assertCode("siws_message_mismatch", () -> verifier.verify("not a siws message".getBytes(StandardCharsets.UTF_8),
                new byte[64], address));
        assertCode("siws_invalid_public_key", () -> verifier.verify(unknown, sign(unknown), "1".repeat(10_000)));
        assertCode("siws_message_mismatch", () -> verifier.verify(new byte[SiwsVerifier.MAX_MESSAGE_BYTES + 1],
                new byte[64], address));
    }

    @Test
    void challengeStoreIsBoundedAndPrunesExpiredChallenges() {
        InMemorySiwsChallengeStore store = new InMemorySiwsChallengeStore(1, clock);
        SiwsVerifier bounded = new SiwsVerifier(store, clock);
        bounded.issue("example.com", null, null, SolanaCluster.MAINNET, Duration.ofMinutes(5), List.of());

        assertThrows(IllegalStateException.class, () -> bounded.issue("example.com", null, null,
                SolanaCluster.MAINNET, Duration.ofMinutes(5), List.of()));
        clock.set(Instant.parse("2026-01-01T00:06:00Z"));
        bounded.issue("example.com", null, null, SolanaCluster.MAINNET, Duration.ofMinutes(5), List.of());
    }

    @Test
    void emptyStatementIsTreatedAsAbsentLikeWalletStandard() {
        SiwsMessage message = new SiwsMessage("example.com", address, "", null, "1", null, null, null, null, null,
                null, List.of());

        assertNull(message.statement());
        assertEquals("example.com wants you to sign in with your Solana account:\n" + address + "\n\nVersion: 1",
                message.toMessage());
    }

    @Test
    void messagesRenderExactlyPerTheSpecAndParseStrictly() {
        SiwsMessage minimal = new SiwsMessage("example.com", address, null, null, null, null, null, null, null, null,
                null, List.of());
        assertEquals("example.com wants you to sign in with your Solana account:\n" + address, minimal.toMessage());

        SiwsMessage full = challenge().toMessage(address);
        String text = full.toMessage();
        assertEquals("example.com wants you to sign in with your Solana account:\n" + address + "\n\n"
                + "Sign in to Example\n\n"
                + "URI: https://example.com/login\nVersion: 1\nChain ID: mainnet\nNonce: " + full.nonce() + "\n"
                + "Issued At: 2026-01-01T00:00:00Z\nExpiration Time: 2026-01-01T00:05:00Z\n"
                + "Resources:\n- https://example.com/terms", text);
        assertEquals(full, SiwsMessage.parse(text));

        SiwsMessage noStatement = new SiwsMessage("example.com", address, null, null, "1", "devnet", "abc", null,
                null, null, null, List.of());
        assertEquals(noStatement, SiwsMessage.parse(noStatement.toMessage()));
        assertNull(SiwsMessage.parse(noStatement.toMessage()).statement());

        for (String invalid : List.of(text.replace("Version: 1\nChain ID", "Chain ID: mainnet\nVersion: 1\nX"),
                text + "\n", text.replace("\n", "\r\n"), text.replace("URI:", "Uri:"),
                "example.com wants you to sign in with your Ethereum account:\n" + address)) {
            assertThrows(IllegalArgumentException.class, () -> SiwsMessage.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> new SiwsMessage("example.com", "0xabc", null, null, null,
                null, null, null, null, null, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SiwsMessage("example.com", address, "two\nlines",
                null, null, null, null, null, null, null, null, List.of()));
    }

    @Test
    void verifiedSolanaSignInIsAnIdentityButNotAnEvmSession() {
        // EVM 组件通过 current() 读取会话：Solana 身份必须表现为“未登录”，只能经 currentIdentity() 读取
        com.vaadin.flow.server.VaadinSession session = new com.vaadin.flow.server.VaadinSession(null) {
            private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
            @Override public boolean hasLock() { return true; }
            @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
            @Override public Object getAttribute(String name) { return attributes.get(name); }
        };
        com.vaadin.flow.server.VaadinSession.setCurrent(session);
        try {
            SiwsChallenge challenge = challenge();
            byte[] message = challenge.toMessage(address).toMessage().getBytes(StandardCharsets.UTF_8);
            com.wontlost.web3.siwe.Web3Session.signIn(verifier.verify(message, sign(message), address));

            assertEquals(address, com.wontlost.web3.siwe.Web3Session.currentIdentity().orElseThrow().account().address());
            assertTrue(com.wontlost.web3.siwe.Web3Session.current().isEmpty());
        } finally {
            com.vaadin.flow.server.VaadinSession.setCurrent(null);
        }
    }

    private SiwsChallenge challenge() {
        return verifier.issue("example.com", "https://example.com/login", "Sign in to Example", SolanaCluster.MAINNET,
                Duration.ofMinutes(5), List.of("https://example.com/terms"));
    }

    private byte[] sign(byte[] message) {
        return sign(message, key);
    }

    private static byte[] sign(byte[] message, Ed25519PrivateKeyParameters privateKey) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable executable) {
        SiwsException failure = assertThrows(SiwsException.class, executable);
        assertEquals(code, failure.code());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            now = value;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
