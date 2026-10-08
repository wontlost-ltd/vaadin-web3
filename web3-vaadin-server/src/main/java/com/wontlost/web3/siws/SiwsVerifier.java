package com.wontlost.web3.siws;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import com.wontlost.web3.identity.Caip2;
import com.wontlost.web3.identity.ChainAccount;

/**
 * Sign-In With Solana 服务端验证。
 * <p>
 * {@link #issue} 生成挑战并<strong>保存在服务端</strong>（以 nonce 为键），挑战内容交给钱包 {@code solana:signIn}。
 * 钱包返回签名的消息字节、签名与公钥后由 {@link #verify} 校验：
 * <ol>
 *   <li>从签名文本解析出 nonce，并只使用服务端保存的同一挑战——客户端无法替换域名、URI 等字段；</li>
 *   <li>签名的字节必须与该挑战加上签名者地址渲染出的消息逐字节一致；</li>
 *   <li>以 Ed25519 校验签名覆盖的正是这些字节；</li>
 *   <li>签发时间（允许 {@link #ISSUED_AT_SKEW} 的节点时钟偏差）与过期时间；</li>
 *   <li>最后原子地消费挑战，阻止重放；失败的尝试不会消费挑战。</li>
 * </ol>
 * 任何失败都抛出带稳定失败码的 {@link SiwsException}。
 */
public final class SiwsVerifier {
    /** 多节点时钟偏差容忍：签发节点的时钟可能略快于验证节点。 */
    public static final Duration ISSUED_AT_SKEW = Duration.ofSeconds(60);
    /** 签名消息的最大字节数。 */
    public static final int MAX_MESSAGE_BYTES = 8 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SiwsChallengeStore challenges;
    private final Clock clock;

    public SiwsVerifier(SiwsChallengeStore challenges, Clock clock) {
        this.challenges = Objects.requireNonNull(challenges);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 生成并在服务端保存挑战；有效期须为正。 */
    public SiwsChallenge issue(String domain, String uri, String statement, SolanaCluster cluster, Duration ttl,
            List<String> resources) {
        Objects.requireNonNull(cluster);
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("SIWS challenge ttl must be positive");
        }
        byte[] nonceBytes = new byte[16];
        RANDOM.nextBytes(nonceBytes);
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        SiwsChallenge challenge = new SiwsChallenge(domain, statement, uri, cluster, HexFormat.of().formatHex(nonceBytes),
                issuedAt.toString(), issuedAt.plus(ttl).toString(), resources);
        challenges.save(challenge);
        return challenge;
    }

    /**
     * 校验钱包的 {@code solana:signIn} 输出。挑战由服务端按签名文本中的 nonce 查找，调用方不能提供挑战。
     *
     * @param signedMessage 钱包返回的 {@code signedMessage} 字节
     * @param signature     钱包返回的 Ed25519 签名（64 字节）
     * @param publicKey     签名账户的 base58 公钥
     */
    public VerifiedSolanaSignIn verify(byte[] signedMessage, byte[] signature, String publicKey) {
        if (signedMessage == null || signature == null || signature.length != 64) {
            throw new SiwsException("siws_invalid_signature");
        }
        // 签名消息长度有界：合法 SIWS 消息远小于此，先拒绝超大输入再解析
        if (signedMessage.length > MAX_MESSAGE_BYTES) {
            throw new SiwsException("siws_message_mismatch");
        }
        byte[] key;
        try {
            key = Base58.decode(publicKey, 32);
        } catch (IllegalArgumentException exception) {
            throw new SiwsException("siws_invalid_public_key");
        }
        String nonce;
        try {
            nonce = SiwsMessage.parse(new String(signedMessage, StandardCharsets.UTF_8)).nonce();
        } catch (IllegalArgumentException exception) {
            throw new SiwsException("siws_message_mismatch");
        }
        SiwsChallenge challenge = challenges.find(nonce).orElseThrow(() -> new SiwsException("siws_unknown_challenge"));
        SiwsMessage expected = challenge.toMessage(publicKey);
        if (!MessageDigest.isEqual(signedMessage, expected.toMessage().getBytes(StandardCharsets.UTF_8))) {
            throw new SiwsException("siws_message_mismatch");
        }
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(key, 0));
        verifier.update(signedMessage, 0, signedMessage.length);
        if (!verifier.verifySignature(signature)) {
            throw new SiwsException("siws_invalid_signature");
        }
        Instant now = clock.instant();
        try {
            if (now.plus(ISSUED_AT_SKEW).isBefore(Instant.parse(challenge.issuedAt()))) {
                throw new SiwsException("siws_not_yet_valid");
            }
            if (!now.isBefore(Instant.parse(challenge.expirationTime()))) {
                throw new SiwsException("siws_expired");
            }
        } catch (DateTimeParseException exception) {
            throw new SiwsException("siws_invalid_challenge");
        }
        // 只在全部校验通过后原子地消费：无效请求不能耗尽合法挑战，并发重放只有一个成功
        if (!challenges.consume(nonce)) {
            throw new SiwsException("siws_nonce_reused");
        }
        ChainAccount account = new ChainAccount(Caip2.SOLANA, challenge.cluster().reference(), publicKey);
        return new VerifiedSolanaSignIn(account, expected, now);
    }
}
