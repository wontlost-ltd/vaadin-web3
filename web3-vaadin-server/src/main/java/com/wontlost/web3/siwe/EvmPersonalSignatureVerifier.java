package com.wontlost.web3.siwe;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;

import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import com.wontlost.web3.chain.ChainRegistry;
import com.wontlost.web3.chain.EthRpcClient;
import com.wontlost.web3.chain.SignatureValidator;

public final class EvmPersonalSignatureVerifier {
    private EvmPersonalSignatureVerifier() { }

    public static boolean verify(String address, long chainId, byte[] message, String signatureHex,
            ChainRegistry chains) {
        byte[] signature;
        try {
            signature = Numeric.hexStringToByteArray(signatureHex);
        } catch (RuntimeException exception) {
            throw new SiweException(SiweException.Reason.SIGNATURE_INVALID,
                    "SIWE signature is invalid", exception);
        }
        String recovered = recoverAddress(message, signature);
        if (recovered != null && recovered.equalsIgnoreCase(address)) {
            return true;
        }
        Optional<EthRpcClient> client = chains == null ? Optional.empty() : chains.get(chainId);
        if (client.isEmpty()) {
            if (recovered == null) {
                throw new SiweException(SiweException.Reason.SIGNATURE_INVALID, "SIWE signature is invalid");
            }
            throw new SiweException(SiweException.Reason.ADDRESS_MISMATCH,
                    "Signature does not match SIWE address");
        }
        try {
            if (!SignatureValidator.isValidSignature(client.get(), address,
                    Sign.getEthereumMessageHash(message), signature)) {
                if (recovered == null) {
                    throw new SiweException(SiweException.Reason.SIGNATURE_INVALID, "SIWE signature is invalid");
                }
                throw new SiweException(SiweException.Reason.ADDRESS_MISMATCH,
                        "Signature does not match SIWE address");
            }
            return true;
        } catch (SiweException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SiweException(SiweException.Reason.SIGNATURE_UNVERIFIABLE,
                    "Smart-contract wallet signature could not be verified on chain", exception);
        }
    }

    private static String recoverAddress(byte[] message, byte[] signature) {
        if (signature.length != 65) {
            return null;
        }
        byte[] copy = signature.clone();
        byte v = copy[64];
        if (v == 0 || v == 1) {
            copy[64] = (byte) (v + 27);
        }
        try {
            Sign.SignatureData data = new Sign.SignatureData(copy[64],
                    Arrays.copyOfRange(copy, 0, 32), Arrays.copyOfRange(copy, 32, 64));
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(message, data);
            return Keys.toChecksumAddress("0x" + Keys.getAddress(publicKey));
        } catch (Exception exception) {
            return null;
        }
    }
}
