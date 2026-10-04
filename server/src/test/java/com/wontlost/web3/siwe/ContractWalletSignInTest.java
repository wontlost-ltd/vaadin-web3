package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 合约钱包（ERC-1271 / ERC-6492）登录路径：链上校验由假 RPC 应答模拟，校验器本身由 SignatureValidatorTest 覆盖。 */
class ContractWalletSignInTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final String WALLET = Keys.toChecksumAddress("0x00000000000000000000000000000000c0ffee00");
    private static final ECKeyPair OWNER = ECKeyPair.create(BigInteger.ONE);
    private static final String OWNER_ADDRESS = Keys.toChecksumAddress("0x" + Keys.getAddress(OWNER.getPublicKey()));
    private static final String TRUE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x01\"}";
    private static final String FALSE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x00\"}";
    private static final String REVERT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":3,\"message\":\"execution reverted\"}}";

    private final List<JsonNode> requests = new ArrayList<>();
    private final InMemoryNonceStore nonces = new InMemoryNonceStore(Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test void ownerSignatureForContractWalletIsAcceptedWhenTheWalletConfirms() {
        // Safe 等钱包的单签名者签名是 65 字节有效 ECDSA，但恢复出的是所有者地址而非钱包地址
        String nonce = nonces.issue();
        String message = message(WALLET, nonce);
        String signature = sign(message, OWNER);

        VerifiedSignIn signIn = verifier(registry(TRUE)).verify(message, signature, expectations());

        assertEquals(WALLET, signIn.address());
        assertEquals(1, requests.size());
        JsonNode call = requests.get(0).path("params").get(0);
        String expectedHash = Numeric.toHexString(Sign.getEthereumMessageHash(message.getBytes(StandardCharsets.UTF_8)));
        String data = call.path("data").asString();
        // 构造参数依次为 signer、EIP-191 消息哈希、签名
        assertTrue(data.contains(Numeric.cleanHexPrefix(WALLET).toLowerCase()), "signer is encoded");
        assertTrue(data.contains(Numeric.cleanHexPrefix(expectedHash)), "personal_sign hash is encoded");
        assertTrue(data.endsWith(Numeric.cleanHexPrefix(signature).toLowerCase() + "0".repeat(62)), "signature is encoded");
        assertReason(SiweException.Reason.NONCE_INVALID,
                () -> verifier(registry(TRUE)).verify(message, signature, expectations()));
    }

    @Test void arbitraryLengthCounterfactualSignatureIsVerifiedOnChain() {
        String nonce = nonces.issue();
        String message = message(WALLET, nonce);
        String wrapped = "0x" + "11".repeat(200) + "6492".repeat(16);

        assertEquals(WALLET, verifier(registry(TRUE)).verify(message, wrapped, expectations()).address());
    }

    @Test void rejectedContractSignatureKeepsTheNonceAndReportsTheMismatch() {
        String nonce = nonces.issue();
        String message = message(WALLET, nonce);

        assertReason(SiweException.Reason.ADDRESS_MISMATCH,
                () -> verifier(registry(FALSE)).verify(message, sign(message, OWNER), expectations()));
        assertReason(SiweException.Reason.SIGNATURE_INVALID,
                () -> verifier(registry(REVERT)).verify(message, "0x" + "22".repeat(90), expectations()));
        assertTrue(nonces.isActive(nonce), "failed attempts must not burn the nonce");
    }

    @Test void unreachableNodeIsReportedAsUnverifiable() {
        String nonce = nonces.issue();
        String message = message(WALLET, nonce);
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new EthRpcClient(request -> { throw new IOException("connection refused"); }));

        assertReason(SiweException.Reason.SIGNATURE_UNVERIFIABLE,
                () -> verifier(chains).verify(message, sign(message, OWNER), expectations()));
        assertTrue(nonces.isActive(nonce));
    }

    @Test void externallyOwnedAccountsNeverTouchTheNetwork() {
        String nonce = nonces.issue();
        String message = message(OWNER_ADDRESS, nonce);

        assertEquals(OWNER_ADDRESS, verifier(registry(FALSE)).verify(message, sign(message, OWNER), expectations()).address());
        assertEquals(0, requests.size());
    }

    @Test void unknownNonceIsRejectedBeforeAnyRpcCall() {
        String message = message(WALLET, "unknownnonce1234");

        assertReason(SiweException.Reason.NONCE_INVALID,
                () -> verifier(registry(TRUE)).verify(message, sign(message, OWNER), expectations()));
        assertEquals(0, requests.size());
    }

    @Test void withoutAClientForTheChainContractWalletsAreRejectedOffline() {
        String nonce = nonces.issue();
        String message = message(WALLET, nonce);

        assertReason(SiweException.Reason.ADDRESS_MISMATCH,
                () -> new SiweVerifier(nonces, Clock.fixed(NOW, ZoneOffset.UTC)).verify(message, sign(message, OWNER), expectations()));
        ChainRegistry otherChain = new ChainRegistry();
        otherChain.register(8453, new EthRpcClient(request -> { requests.add(MAPPER.readTree(request)); return TRUE; }));
        assertReason(SiweException.Reason.SIGNATURE_INVALID,
                () -> verifier(otherChain).verify(message, "0x" + "33".repeat(90), expectations()));
        assertEquals(0, requests.size());
    }

    private ChainRegistry registry(String response) {
        ChainRegistry chains = new ChainRegistry();
        chains.register(1, new EthRpcClient(request -> {
            requests.add(MAPPER.readTree(request));
            return response;
        }));
        return chains;
    }

    private SiweVerifier verifier(ChainRegistry chains) {
        return new SiweVerifier(nonces, Clock.fixed(NOW, ZoneOffset.UTC), chains);
    }

    private static String message(String address, String nonce) {
        return SiweMessage.builder().scheme("https").domain("example.com").address(address)
                .uri("https://example.com/login").chainId(1).nonce(nonce).issuedAt(NOW).build().toMessage();
    }

    private static SiweExpectations expectations() {
        return SiweExpectations.forDomain("example.com").withUri("https://example.com/login");
    }

    private static String sign(String message, ECKeyPair keyPair) {
        Sign.SignatureData signature = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), keyPair);
        return "0x" + HexFormat.of().formatHex(signature.getR())
                + HexFormat.of().formatHex(signature.getS()) + HexFormat.of().formatHex(signature.getV());
    }

    private static void assertReason(SiweException.Reason reason, Runnable action) {
        assertEquals(reason, assertThrows(SiweException.class, action::run).getReason());
    }
}
