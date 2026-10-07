package com.wontlost.web3.x402.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;

import com.wontlost.web3.x402.payment.Eip3009TypedDataFactory;
import com.wontlost.web3.x402.payment.ResourcePolicy;

import tools.jackson.databind.ObjectMapper;

class X402CodecTest {
    private final JacksonX402Codec codec = new JacksonX402Codec();

    @Test void encodesAndDecodesCanonicalUnpaddedV2Payload() {
        String address = "0x0000000000000000000000000000000000000001";
        PaymentRequirements requirements = requirements(address);
        PaymentPayload payload = new PaymentPayload(2, new X402Resource("https://example.test/a", null, null), requirements,
                new Eip3009Payload("0x" + "11".repeat(64) + "1b", new TransferAuthorization(address, address,
                        "1000000", "1", "10", "0x" + "22".repeat(32))));
        String encoded = codec.encodePaymentPayload(payload);
        assertFalse(encoded.endsWith("="));
        assertEquals(payload, codec.decodePaymentPayload(encoded));
    }

    @Test void rejectsUrlSafeBase64MalformedUtf8DuplicatesAndDeepJson() {
        assertThrows(IllegalArgumentException.class, () -> codec.decodePaymentPayload("____"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodePaymentPayload(Base64s.of(new byte[]{(byte) 0xc3, 0x28})));
        assertThrows(IllegalArgumentException.class, () -> codec.decodePaymentPayload(Base64s.json("{\"x402Version\":2,\"x402Version\":2}")));
        assertThrows(IllegalArgumentException.class, () -> codec.decodePaymentPayload(Base64s.json("{\"x402Version\":2,\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}")));
        assertThrows(IllegalArgumentException.class, () -> new JacksonX402Codec(8, 8).decodePaymentPayload("eyJ4NDAyVmVyc2lvbiI6Mn0"));
    }

    @Test void validatesAmountsNetworksAddressesNoncesAndSignatureLengths() {
        for (String value : new String[]{"-1", "+1", "1.0", "1e3", "01", "0", "9".repeat(79)})
            assertThrows(IllegalArgumentException.class, () -> X402Validation.amount(value));
        assertEquals(BigInteger.valueOf(1), X402Validation.amount("1"));
        assertEquals(84532L, X402Validation.chainId("eip155:84532", Set.of(84532L)));
        for (String network : new String[]{"eip155:0", "eip155:01", "solana:mainnet", "eip155:999"})
            assertThrows(IllegalArgumentException.class, () -> X402Validation.chainId(network, Set.of(84532L)));
        assertThrows(IllegalArgumentException.class, () -> X402Validation.address("0x0000000000000000000000000000000000000000"));
        assertThrows(IllegalArgumentException.class, () -> X402Validation.address("0x52908400098527886e0F7030069857D2E4169EE7"));
        assertThrows(IllegalArgumentException.class, () -> X402Validation.nonce("0x1234"));
        assertThrows(IllegalArgumentException.class, () -> X402Validation.signature("0x1234"));
        assertThrows(IllegalArgumentException.class, () -> codec.decodePaymentRequired(Base64s.json("{\"x402Version\":2,\"accepts\":[{\"scheme\":\"permit2\"}]}")));
    }

    @Test void buildsTypedDataAndMatchesWeb3jSignatureRecoveryVector() throws Exception {
        ECKeyPair key = ECKeyPair.create(BigInteger.ONE);
        String from = Keys.toChecksumAddress("0x" + Keys.getAddress(key.getPublicKey()));
        String asset = "0x0000000000000000000000000000000000000002";
        String payTo = "0x0000000000000000000000000000000000000003";
        ResourcePolicy policy = new ResourcePolicy("article", "v1", new X402Resource("https://example.test/a", null, null),
                "eip155:84532", BigInteger.valueOf(123), asset, payTo, 60, "Test USD", "2");
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC);
        Eip3009TypedDataFactory factory = new Eip3009TypedDataFactory(clock, () -> new byte[32]);
        var generated = factory.create(policy, from);
        assertEquals("1699999400", generated.authorization().validAfter());
        assertEquals("1700000060", generated.authorization().validBefore());
        assertEquals("0x" + "00".repeat(32), generated.authorization().nonce());
        var tree = new ObjectMapper().readTree(generated.typedDataJson());
        assertEquals("TransferWithAuthorization", tree.path("primaryType").asString());
        assertEquals(asset, tree.path("domain").path("verifyingContract").asString());
        assertEquals("Test USD", tree.path("domain").path("name").asString());
        assertEquals("123", tree.path("message").path("value").asString());
        String signature = encode(Sign.signTypedData(generated.typedDataJson(), key));
        var sig = new Sign.SignatureData(HexFormat.of().parseHex(signature.substring(130)),
                HexFormat.of().parseHex(signature.substring(2, 66)), HexFormat.of().parseHex(signature.substring(66, 130)));
        BigInteger recovered = Sign.signedMessageHashToKey(new StructuredDataEncoder(generated.typedDataJson()).hashStructuredData(), sig);
        assertEquals(from, Keys.toChecksumAddress("0x" + Keys.getAddress(recovered)));
    }

    @Test void rejectsTimestampOverflow() {
        ResourcePolicy policy = new ResourcePolicy("r", "1", new X402Resource("https://example.test", null, null),
                "eip155:1", BigInteger.ONE, "0x0000000000000000000000000000000000000002",
                "0x0000000000000000000000000000000000000003", 300, "Token", "1");
        var factory = new Eip3009TypedDataFactory(Clock.fixed(Instant.MAX, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> factory.create(policy, "0x0000000000000000000000000000000000000004"));
    }

    private PaymentRequirements requirements(String address) {
        var mapper = new ObjectMapper();
        return new PaymentRequirements("exact", "eip155:1", "1000000", address, address, 300,
                Map.of("name", mapper.valueToTree("Test"), "version", mapper.valueToTree("1"),
                        "assetTransferMethod", mapper.valueToTree("eip3009"), "paymentFlow", mapper.valueToTree("authorization")));
    }
    private static String encode(Sign.SignatureData signature) {
        byte[] bytes = new byte[65]; System.arraycopy(signature.getR(), 0, bytes, 0, 32);
        System.arraycopy(signature.getS(), 0, bytes, 32, 32); bytes[64] = signature.getV()[0];
        return "0x" + HexFormat.of().formatHex(bytes);
    }
    private static final class Base64s {
        static String of(byte[] bytes) { return java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes); }
        static String json(String value) { return of(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
