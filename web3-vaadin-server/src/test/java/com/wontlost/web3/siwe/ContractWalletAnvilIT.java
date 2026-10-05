package com.wontlost.web3.siwe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.HttpJsonRpcTransport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 EVM 上的合约钱包登录：部署 src/test/resources/contracts/Wallets.sol 中的 CREATE2 工厂，
 * 覆盖已部署的 ERC-1271 钱包与未部署的 ERC-6492 钱包。
 * <p>
 * 默认跳过；本地运行：{@code anvil --port 8546} 后执行
 * {@code ANVIL_RPC=http://127.0.0.1:8546 mvn -pl server test -Dtest=ContractWalletAnvilIT}。
 */
@EnabledIfEnvironmentVariable(named = "ANVIL_RPC", matches = "https?://.+")
class ContractWalletAnvilIT {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ECKeyPair OWNER = ECKeyPair.create(BigInteger.ONE);
    private static final ECKeyPair STRANGER = ECKeyPair.create(BigInteger.TWO);
    private static final String MAGIC = "6492".repeat(16);
    private static final String RPC = System.getenv("ANVIL_RPC");

    private static ChainRegistry chains;
    private static long chainId;
    private static String factory;
    private static String deployed;
    private static String counterfactual;
    private static String counterfactualDeployCall;

    @BeforeAll static void deployContracts() throws Exception {
        EthRpcClient client = new EthRpcClient(RPC);
        chainId = client.chainId();
        chains = new ChainRegistry();
        chains.register(chainId, client);
        String sender = rpc("eth_accounts", List.of()).get(0).asString();
        String bytecode = MAPPER.readTree(ContractWalletAnvilIT.class
                .getResourceAsStream("/contracts/wallets-bytecode.json").readAllBytes()).path("WalletFactory").asString();
        factory = receipt(send(sender, null, bytecode)).path("contractAddress").asString();

        String ownerAddress = Keys.getAddress(OWNER.getPublicKey());
        // 每次运行使用新的 salt，避免与同一 anvil 实例上先前运行留下的钱包冲突
        long run = System.nanoTime();
        String deployedCall = deployCall(ownerAddress, BigInteger.valueOf(run));
        counterfactualDeployCall = deployCall(ownerAddress, BigInteger.valueOf(run + 1));
        receipt(send(sender, factory, deployedCall));
        deployed = predict(ownerAddress, BigInteger.valueOf(run));
        counterfactual = predict(ownerAddress, BigInteger.valueOf(run + 1));
        assertEquals("0x", rpc("eth_getCode", List.of(counterfactual, "latest")).asString());
    }

    @Test void deployedWalletAcceptsOwnerSignature() {
        assertEquals(Keys.toChecksumAddress(deployed), signIn(deployed, m -> sign(m, OWNER)).address());
    }

    @Test void deployedWalletRejectsStrangerSignature() {
        assertReason(SiweException.Reason.ADDRESS_MISMATCH, () -> signIn(deployed, m -> sign(m, STRANGER)));
    }

    @Test void counterfactualWalletAcceptsWrappedOwnerSignatureWithoutSideEffects() {
        assertEquals(Keys.toChecksumAddress(counterfactual),
                signIn(counterfactual, m -> wrap(sign(m, OWNER))).address());
        // 校验在 eth_call 中模拟部署，链上状态不变
        assertEquals("0x", rpc("eth_getCode", List.of(counterfactual, "latest")).asString());
    }

    @Test void counterfactualWalletRejectsWrappedStrangerSignature() {
        assertReason(SiweException.Reason.SIGNATURE_INVALID, () -> signIn(counterfactual, m -> wrap(sign(m, STRANGER))));
    }

    @Test void counterfactualWalletWithoutWrapperIsRejected() {
        assertReason(SiweException.Reason.ADDRESS_MISMATCH, () -> signIn(counterfactual, m -> sign(m, OWNER)));
    }

    private interface Signer { String sign(String message); }

    private static VerifiedSignIn signIn(String address, Signer signer) {
        InMemoryNonceStore nonces = new InMemoryNonceStore();
        String message = SiweMessage.builder().scheme("http").domain("localhost").address(address)
                .uri("http://localhost").chainId(chainId).nonce(nonces.issue()).issuedAt(Instant.now()).build().toMessage();
        return new SiweVerifier(nonces, Clock.systemUTC(), chains)
                .verify(message, signer.sign(message), SiweExpectations.forDomain("localhost"));
    }

    private static String wrap(String innerSignature) {
        String encoded = FunctionEncoder.encodeConstructor(List.of(new Address(factory),
                new DynamicBytes(Numeric.hexStringToByteArray(counterfactualDeployCall)),
                new DynamicBytes(Numeric.hexStringToByteArray(innerSignature))));
        return "0x" + Numeric.cleanHexPrefix(encoded) + MAGIC;
    }

    private static String deployCall(String owner, BigInteger salt) {
        return FunctionEncoder.encode(new Function("deploy", List.of(new Address(owner), new Bytes32(salt32(salt))), List.of()));
    }

    private static String predict(String owner, BigInteger salt) {
        String data = FunctionEncoder.encode(new Function("predict", List.of(new Address(owner), new Bytes32(salt32(salt))), List.of()));
        String result = chains.get(chainId).orElseThrow().call(factory, data, "latest");
        return "0x" + Numeric.cleanHexPrefix(result).substring(24);
    }

    private static byte[] salt32(BigInteger value) {
        return Numeric.toBytesPadded(value, 32);
    }

    private static String sign(String message, ECKeyPair key) {
        Sign.SignatureData signature = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), key);
        return "0x" + HexFormat.of().formatHex(signature.getR())
                + HexFormat.of().formatHex(signature.getS()) + HexFormat.of().formatHex(signature.getV());
    }

    private static String send(String from, String to, String data) {
        var tx = MAPPER.createObjectNode().put("from", from).put("data", data);
        if (to != null) tx.put("to", to);
        return rpc("eth_sendTransaction", List.of(tx)).asString();
    }

    private static JsonNode receipt(String hash) {
        JsonNode receipt = rpc("eth_getTransactionReceipt", List.of(hash));
        assertEquals("0x1", receipt.path("status").asString(), "transaction " + hash + " failed");
        return receipt;
    }

    private static JsonNode rpc(String method, List<?> params) {
        try {
            var request = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
            request.set("params", MAPPER.valueToTree(params));
            JsonNode response = MAPPER.readTree(new HttpJsonRpcTransport(RPC).send(request.toString()));
            if (response.has("error")) throw new IllegalStateException(response.path("error").toString());
            return response.path("result");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertReason(SiweException.Reason reason, Runnable action) {
        assertEquals(reason, assertThrows(SiweException.class, action::run).getReason());
    }
}
