package com.wontlost.web3.siwe;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.SignatureValidator;

/**
 * Verifies EIP-4361 personal_sign messages.
 * <p>
 * Signatures of externally owned accounts are verified locally with ECDSA recovery. When a {@link ChainRegistry} is
 * supplied and the message's chain has an RPC client, signatures that do not recover to the message address are
 * also checked on chain with {@link SignatureValidator}, which accepts smart-contract wallets
 * (<a href="https://eips.ethereum.org/EIPS/eip-1271">ERC-1271</a>), including wallets that are not deployed yet
 * (<a href="https://eips.ethereum.org/EIPS/eip-6492">ERC-6492</a>). That check performs one blocking
 * {@code eth_call}, bounded by the transport timeout.
 */
public final class SiweVerifier {

    private final NonceStore nonces;
    private final Clock clock;
    private final ChainRegistry chains;

    /** Creates a verifier for externally owned accounts only, using the supplied nonce store and clock. */
    public SiweVerifier(NonceStore nonces, Clock clock) {
        this(nonces, clock, null);
    }

    /**
     * Creates a verifier that also accepts smart-contract wallet signatures on the chains registered in
     * {@code chains}; a {@code null} registry verifies externally owned accounts only.
     */
    public SiweVerifier(NonceStore nonces, Clock clock, ChainRegistry chains) {
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.chains = chains;
    }

    /** Verifies a SIWE message and consumes its nonce only after all checks succeed. */
    public VerifiedSignIn verify(String message, String signatureHex, SiweExpectations expected) {
        SiweMessage parsed = SiweMessage.parse(message);
        if (!parsed.getDomain().equals(expected.domain())) {
            throw failure(SiweException.Reason.DOMAIN_MISMATCH, "SIWE domain did not match");
        }
        if (expected.uri() != null && !expected.uri().equals(parsed.getUri())) {
            throw failure(SiweException.Reason.URI_MISMATCH, "SIWE URI did not match");
        }
        if (!expected.allowedChainIds().isEmpty() && !expected.allowedChainIds().contains(parsed.getChainId())) {
            throw failure(SiweException.Reason.CHAIN_NOT_ALLOWED, "SIWE chain is not allowed");
        }

        Instant now = clock.instant();
        if (parsed.getIssuedAt().isAfter(now.plus(Duration.ofMinutes(1)))) {
            throw failure(SiweException.Reason.NOT_YET_VALID, "SIWE issuedAt is in the future");
        }
        if (parsed.getExpirationTime() != null && !parsed.getExpirationTime().isAfter(now)) {
            throw failure(SiweException.Reason.EXPIRED, "SIWE message has expired");
        }
        if (parsed.getNotBefore() != null && parsed.getNotBefore().isAfter(now)) {
            throw failure(SiweException.Reason.NOT_YET_VALID, "SIWE message is not yet valid");
        }
        if (expected.maxAge() != null && !parsed.getIssuedAt().isAfter(now.minus(expected.maxAge()))) {
            throw failure(SiweException.Reason.TOO_OLD, "SIWE message is too old");
        }

        byte[] signature;
        try {
            signature = Numeric.hexStringToByteArray(signatureHex);
        } catch (RuntimeException exception) {
            throw new SiweException(SiweException.Reason.SIGNATURE_INVALID, "SIWE signature is invalid", exception);
        }
        byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
        String recovered = recoverAddress(messageBytes, signature);
        // ECDSA 恢复出消息地址本身即视为有效，不再上链：合约地址不可能由私钥推导，
        // 唯一"有代码且有私钥"的账户是 EIP-7702 委托的 EOA，其私钥始终拥有账户的完全控制权。
        // 这与 viem verifyHash 的 'eoa' 模式一致，也让普通钱包登录无需 RPC。
        if (recovered == null || !recovered.equalsIgnoreCase(parsed.getAddress())) {
            // ECDSA 未能证明该地址：按 ERC-6492 规范，不同地址的 ecrecover 结果不能直接判失败，
            // 合约钱包（如 Safe 的单签名者签名）恰好也是 65 字节的有效 ECDSA 签名。
            Optional<EthRpcClient> client = chains == null ? Optional.empty() : chains.get(parsed.getChainId());
            // 链上校验有成本：先确认 nonce 仍有效（不消费），避免伪造请求被放大为 RPC 调用
            if (client.isPresent() && !nonces.isActive(parsed.getNonce())) {
                throw failure(SiweException.Reason.NONCE_INVALID, "SIWE nonce is invalid or already used");
            }
            if (client.isEmpty() || !isValidContractSignature(client.get(), parsed.getAddress(), messageBytes, signature)) {
                throw recovered == null
                        ? failure(SiweException.Reason.SIGNATURE_INVALID, "SIWE signature is invalid")
                        : failure(SiweException.Reason.ADDRESS_MISMATCH, "Signature does not match SIWE address");
            }
        }
        if (!nonces.consume(parsed.getNonce())) {
            throw failure(SiweException.Reason.NONCE_INVALID, "SIWE nonce is invalid or already used");
        }
        return new VerifiedSignIn(parsed.getAddress(), parsed.getChainId(), parsed, now);
    }

    /** ECDSA 恢复签名者地址；签名不是有效的 65 字节 r/s/v 时返回 null。 */
    private static String recoverAddress(byte[] message, byte[] signature) {
        if (signature.length != 65) return null;
        byte[] copy = signature.clone();
        byte v = copy[64];
        if (v == 0 || v == 1) {
            copy[64] = (byte) (v + 27);
        }
        try {
            Sign.SignatureData signatureData = new Sign.SignatureData(copy[64],
                    Arrays.copyOfRange(copy, 0, 32), Arrays.copyOfRange(copy, 32, 64));
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(message, signatureData);
            return Keys.toChecksumAddress("0x" + Keys.getAddress(publicKey));
        } catch (Exception exception) {
            return null;
        }
    }

    private static boolean isValidContractSignature(EthRpcClient client, String address, byte[] message, byte[] signature) {
        try {
            return SignatureValidator.isValidSignature(client, address, Sign.getEthereumMessageHash(message), signature);
        } catch (RuntimeException exception) {
            // 节点不可达或返回非 revert 错误：无法判断签名真伪，与"签名无效"区分开，便于界面提示重试
            throw new SiweException(SiweException.Reason.SIGNATURE_UNVERIFIABLE,
                    "Smart-contract wallet signature could not be verified on chain", exception);
        }
    }

    private static SiweException failure(SiweException.Reason reason, String message) {
        return new SiweException(reason, message);
    }
}
