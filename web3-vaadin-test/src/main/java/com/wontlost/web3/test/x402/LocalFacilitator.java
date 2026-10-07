package com.wontlost.web3.test.x402;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

import com.wontlost.web3.ServerWallet;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.x402.payment.Eip3009TypedDataFactory;
import com.wontlost.web3.x402.payment.FacilitatorClient;
import com.wontlost.web3.x402.payment.ResourcePolicy;
import com.wontlost.web3.x402.payment.SettlementResult;
import com.wontlost.web3.x402.payment.SettlementState;
import com.wontlost.web3.x402.payment.SupportedResponse;
import com.wontlost.web3.x402.payment.VerifyResult;
import com.wontlost.web3.x402.protocol.PaymentPayload;
import com.wontlost.web3.x402.protocol.PaymentRequirements;
import com.wontlost.web3.x402.protocol.X402Validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 用于本地 Anvil 测试链的开发 facilitator，不适用于真实资产或生产网络。 */
public final class LocalFacilitator implements FacilitatorClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final EthRpcClient rpc;
    private final ServerWallet relay;
    private final long chainId;
    private final String token;
    private final String payTo;
    private final Clock clock;

    public LocalFacilitator(EthRpcClient rpc, ServerWallet relay, long chainId, String token, String payTo) {
        this(rpc, relay, chainId, token, payTo, null);
    }
    public LocalFacilitator(EthRpcClient rpc, ServerWallet relay, long chainId, String token, String payTo, Clock clock) {
        this.rpc = rpc; this.relay = relay; this.chainId = chainId;
        this.token = X402Validation.address(token); this.payTo = X402Validation.address(payTo); this.clock = clock;
        if (rpc.chainId() != chainId) throw new IllegalArgumentException("local facilitator chain mismatch");
    }

    @Override public SupportedResponse supported() { return new SupportedResponse(2, List.of("exact:eip155:eip3009")); }

    @Override public VerifyResult verify(PaymentPayload payload, PaymentRequirements requirements) {
        try {
            var auth = payload.payload().authorization();
            if (payload.x402Version() != 2 || requirements == null || !"exact".equals(requirements.scheme())
                    || !requirements.network().equals("eip155:" + chainId) || !requirements.asset().equalsIgnoreCase(token)
                    || !requirements.payTo().equalsIgnoreCase(payTo) || !payload.accepted().equals(requirements)
                    || !X402Validation.address(auth.from()).equalsIgnoreCase(auth.from())
                    || !auth.to().equalsIgnoreCase(payTo) || !auth.value().equals(requirements.amount()))
                return new VerifyResult(false, "payment_mismatch", auth.from());
            BigInteger validAfter = X402Validation.uint(auth.validAfter());
            BigInteger validBefore = X402Validation.uint(auth.validBefore());
            BigInteger now = BigInteger.valueOf(currentTimestamp());
            if (now.compareTo(validAfter) <= 0 || now.compareTo(validBefore) >= 0)
                return new VerifyResult(false, "authorization_expired", auth.from());
            String typedData = typedData(payload, requirements);
            String recovered;
            try { recovered = recover(typedData, payload.payload().signature()); }
            catch (RuntimeException exception) { return new VerifyResult(false, "invalid_signature", auth.from()); }
            if (!recovered.equalsIgnoreCase(auth.from())) return new VerifyResult(false, "invalid_signature", recovered);
            if (readBool(call("authorizationState(address,bytes32)", wordAddress(auth.from()) + wordBytes32(auth.nonce()))))
                return new VerifyResult(false, "authorization_used", auth.from());
            BigInteger balance = new BigInteger(call("balanceOf(address)", wordAddress(auth.from())), 16);
            if (balance.compareTo(new BigInteger(auth.value())) < 0) return new VerifyResult(false, "insufficient_balance", auth.from());
            try {
                rpc.call(token, transferCall(auth, payload.payload().signature()), "latest");
            } catch (RuntimeException exception) {
                return new VerifyResult(false, simulationFailureCode(exception), auth.from());
            }
            return new VerifyResult(true, null, auth.from());
        } catch (RuntimeException exception) {
            return new VerifyResult(false, "invalid_payment", null);
        }
    }

    @Override public SettlementResult settle(PaymentPayload payload, PaymentRequirements requirements) {
        VerifyResult verified = verify(payload, requirements);
        if (!verified.valid()) return new SettlementResult(SettlementState.REJECTED, null, verified.invalidReason(), null);
        try {
            String data = transferCall(payload.payload().authorization(), payload.payload().signature());
            String result = relay.request("eth_sendTransaction", MAPPER.writeValueAsString(List.of(Map.of(
                    "from", relay.accounts().getFirst(), "to", token, "data", data, "value", "0x0")))).join();
            String hash = MAPPER.readTree(result).asString();
            for (int i = 0; i < 50; i++) {
                var receipt = rpc.getTransactionReceipt(hash);
                if (receipt.isPresent()) {
                    if (receipt.get().status()) return new SettlementResult(SettlementState.SETTLED, hash, null, null);
                    return new SettlementResult(SettlementState.REJECTED, hash, "transaction_reverted", null);
                }
                Thread.sleep(100);
            }
            return new SettlementResult(SettlementState.UNKNOWN, hash, "settlement_unknown", null);
        } catch (Exception exception) {
            return new SettlementResult(SettlementState.UNKNOWN, null, "settlement_unknown", null);
        }
    }

    public String typedData(PaymentPayload payload, PaymentRequirements requirements) {
        long parsedChain = X402Validation.chainId(requirements.network(), java.util.Set.of(chainId));
        ResourcePolicy policy = new ResourcePolicy("local", "1",
                new com.wontlost.web3.x402.protocol.X402Resource("http://localhost/local", null, null), requirements.network(),
                new BigInteger(requirements.amount()), token, payTo, requirements.maxTimeoutSeconds(),
                requirements.extra().get("name").asString(), requirements.extra().get("version").asString());
        return Eip3009TypedDataFactory.typedDataJson(policy, payload.payload().authorization(), parsedChain);
    }

    private String call(String signature, String args) { return Numeric.cleanHexPrefix(rpc.call(token, selector(signature) + args, "latest")); }
    private long currentTimestamp() {
        if (clock != null) return clock.instant().getEpochSecond();
        String timestamp = rpc.request("eth_getBlockByNumber", List.of("latest", false)).path("timestamp").asString();
        return new BigInteger(Numeric.cleanHexPrefix(timestamp), 16).longValueExact();
    }
    private String transferCall(com.wontlost.web3.x402.protocol.TransferAuthorization auth, String signature) {
        byte[] sig = Numeric.hexStringToByteArray(signature);
        String v = String.format("%02x", Byte.toUnsignedInt(sig[64]));
        if ("00".equals(v) || "01".equals(v)) v = String.format("%02x", Integer.parseInt(v, 16) + 27);
        String args = wordAddress(auth.from()) + wordAddress(auth.to()) + wordUint(new BigInteger(auth.value()))
                + wordUint(new BigInteger(auth.validAfter())) + wordUint(new BigInteger(auth.validBefore()))
                + wordBytes32(auth.nonce()) + wordUint(new BigInteger(v, 16))
                + HexFormat.of().formatHex(sig, 0, 32) + HexFormat.of().formatHex(sig, 32, 64);
        return "0x" + selector("transferWithAuthorization(address,address,uint256,uint256,uint256,bytes32,uint8,bytes32,bytes32)") + args;
    }
    private String recover(String typedData, String signature) {
        byte[] sig = Numeric.hexStringToByteArray(signature);
        byte v = sig[64]; if (v < 27) v += 27;
        var parts = new Sign.SignatureData(v, java.util.Arrays.copyOfRange(sig, 0, 32), java.util.Arrays.copyOfRange(sig, 32, 64));
        try {
            BigInteger key = Sign.signedMessageHashToKey(new StructuredDataEncoder(typedData).hashStructuredData(), parts);
            return Keys.toChecksumAddress("0x" + Keys.getAddress(key));
        } catch (Exception exception) { throw new IllegalArgumentException("invalid typed signature", exception); }
    }
    private boolean readBool(String value) { return new BigInteger(value, 16).signum() != 0; }
    private static String simulationFailureCode(RuntimeException exception) {
        String details = (exception.getMessage() == null ? "" : exception.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (details.contains("authorization not yet valid")) return "authorization_not_yet_valid";
        if (details.contains("authorization expired")) return "authorization_expired";
        if (details.contains("invalid signature")) return "invalid_signature";
        if (details.contains("insufficient balance")) return "insufficient_balance";
        if (details.contains("authorization used")) return "authorization_used";
        return "simulation_failed";
    }
    private static String selector(String function) { return HexFormat.of().formatHex(Hash.sha3(function.getBytes(StandardCharsets.UTF_8)), 0, 4); }
    private static String wordAddress(String value) { return "0".repeat(24) + Numeric.cleanHexPrefix(value).toLowerCase(java.util.Locale.ROOT); }
    private static String wordBytes32(String value) { return Numeric.cleanHexPrefix(value).toLowerCase(java.util.Locale.ROOT); }
    private static String wordUint(BigInteger value) { return String.format("%064x", value); }
}
