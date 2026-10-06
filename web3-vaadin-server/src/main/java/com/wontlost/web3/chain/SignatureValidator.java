package com.wontlost.web3.chain;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.utils.Numeric;

/**
 * Verifies a signature over a 32-byte hash for any account type in a single {@code eth_call}:
 * <a href="https://eips.ethereum.org/EIPS/eip-6492">ERC-6492</a> signatures of smart-contract wallets that are not
 * deployed yet, <a href="https://eips.ethereum.org/EIPS/eip-1271">ERC-1271</a> signatures of deployed contract
 * wallets, and plain ECDSA signatures of externally owned accounts.
 * <p>
 * The call executes the ERC-6492 off-chain validator as contract-creation code, so no contract has to be deployed
 * and nothing is written to the chain.
 */
public final class SignatureValidator {
    /*
     * 校验器字节码取自 viem 2.57.2（MIT）的 erc6492SignatureValidatorByteCode，
     * 对应 ERC-6492 规范中的参考实现 ValidateSigOffchain：构造函数参数为 (address, bytes32, bytes)，
     * 返回 1 字节布尔值。资源文件 SHA-256：037d6b69e53bae264a9a752be534c6373b3f829fb606456f184d2ba841de6ea4。
     */
    private static final String VALIDATOR_BYTECODE = loadBytecode();

    private SignatureValidator() { }

    /**
     * Returns whether {@code signature} is a valid signature of {@code hash} by {@code signer} on the client's chain.
     * All RPC reads use a pinned endpoint view. A pinned read failure is propagated so callers can retry the operation.
     *
     * @throws EthRpcException when the endpoint reports an error other than an execution revert
     * @throws IllegalStateException when the endpoint cannot be reached
     */
    public static boolean isValidSignature(EthRpcClient client, String signer, byte[] hash, byte[] signature) {
        client = Objects.requireNonNull(client, "client").pinned();
        if (Objects.requireNonNull(hash, "hash").length != 32) throw new IllegalArgumentException("hash must be 32 bytes");
        String result;
        try {
            result = client.call(null, callData(signer, hash, signature), "latest");
        } catch (EthRpcException exception) {
            // 校验器或钱包合约 revert 表示签名无效（格式错误、恢复失败等），与 viem 的处理一致；其余错误向上抛出
            if (isRevert(exception)) return false;
            throw exception;
        }
        // 校验器只返回 1 字节布尔值：0x01 有效，其余一律视为无效
        return result != null && "01".equals(Numeric.cleanHexPrefix(result));
    }

    /** 校验器创建代码 + ABI 编码的构造参数 (address signer, bytes32 hash, bytes signature)。 */
    static String callData(String signer, byte[] hash, byte[] signature) {
        String arguments = FunctionEncoder.encodeConstructor(List.of(new Address(signer),
                new Bytes32(hash), new DynamicBytes(Objects.requireNonNull(signature, "signature"))));
        return VALIDATOR_BYTECODE + Numeric.cleanHexPrefix(arguments);
    }

    private static boolean isRevert(EthRpcException exception) {
        // geth 系节点以 code 3 报告带数据的 revert；其他实现通常为 -32000 且消息含 "revert"
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        return exception.getCode() == 3 || message.contains("revert");
    }

    private static String loadBytecode() {
        try (InputStream in = SignatureValidator.class.getResourceAsStream("erc6492-signature-validator.hex")) {
            if (in == null) throw new IllegalStateException("ERC-6492 validator bytecode resource is missing");
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
