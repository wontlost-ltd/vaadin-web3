package com.wontlost.web3.nft;

import java.math.BigInteger;
import java.util.List;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Uint;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes4;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Numeric;

/** RPC 数据源使用的所有权 ABI 编解码器。 */
public final class NftAbi {
    private static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    private NftAbi() {
    }

    public static String ownerOfData(BigInteger tokenId) {
        return encode("ownerOf", List.of(new Uint256(requireUint256(tokenId))));
    }

    public static String tokenUriData(BigInteger tokenId) {
        return encode("tokenURI", List.of(new Uint256(requireUint256(tokenId))));
    }

    public static String uriData(BigInteger tokenId) {
        return encode("uri", List.of(new Uint256(requireUint256(tokenId))));
    }

    public static String balanceOfData(String owner) {
        return encode("balanceOf", List.of(new Address(NftAddress.normalize(owner))));
    }

    public static String balanceOfData(String owner, BigInteger tokenId) {
        return encode("balanceOf", List.of(new Address(NftAddress.normalize(owner)), new Uint256(requireUint256(tokenId))));
    }

    public static String balanceOfBatchData(List<String> owners, List<BigInteger> tokenIds) {
        if (owners.size() != tokenIds.size() || owners.isEmpty()) {
            throw new IllegalArgumentException("Owner and token ID arrays must be non-empty and equal in length");
        }
        List<Address> normalizedOwners = owners.stream().map(NftAddress::normalize).map(Address::new).toList();
        List<Uint256> normalizedIds = tokenIds.stream().map(NftAbi::requireUint256).map(Uint256::new).toList();
        return encode("balanceOfBatch", List.of(new DynamicArray<>(Address.class, normalizedOwners),
                new DynamicArray<>(Uint256.class, normalizedIds)));
    }

    public static String tokenOfOwnerByIndexData(String owner, BigInteger index) {
        return encode("tokenOfOwnerByIndex", List.of(new Address(NftAddress.normalize(owner)), new Uint256(requireUint256(index))));
    }

    public static String supportsInterfaceData(String interfaceId) {
        if (interfaceId == null || !interfaceId.matches("(?i)0x[0-9a-f]{8}")) {
            throw new IllegalArgumentException("Interface ID must contain exactly four hexadecimal bytes");
        }
        return encode("supportsInterface", List.of(new Bytes4(Numeric.hexStringToByteArray(interfaceId))));
    }

    public static BigInteger decodeUint(String data) {
        return (BigInteger) decode(data, new TypeReference<Uint256>() {
        }).getFirst().getValue();
    }

    public static List<BigInteger> decodeUintArray(String data) {
        List<Type> decoded = decode(data, new TypeReference<DynamicArray<Uint256>>() {
        });
        @SuppressWarnings("unchecked")
        List<Uint256> values = (List<Uint256>) decoded.getFirst().getValue();
        return values.stream().map(value -> (BigInteger) value.getValue()).toList();
    }

    public static boolean decodeBool(String data) {
        return (Boolean) decode(data, new TypeReference<Bool>() {
        }).getFirst().getValue();
    }

    public static String decodeAddress(String data) {
        return (String) decode(data, new TypeReference<Address>() {
        }).getFirst().getValue();
    }

    public static String decodeString(String data) {
        return (String) decode(data, new TypeReference<Utf8String>() {
        }).getFirst().getValue();
    }

    public static BigInteger requireUint256(BigInteger value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_UINT256) > 0) {
            throw new IllegalArgumentException("Value must be an unsigned 256-bit integer");
        }
        return value;
    }

    private static String encode(String name, List<Type> inputs) {
        return FunctionEncoder.encode(new Function(name, inputs, List.of()));
    }

    private static List<Type> decode(String data, TypeReference<?> output) {
        if (data == null || !data.matches("(?i)0x[0-9a-f]+") || (data.length() - 2) % 64 != 0) {
            throw new IllegalArgumentException("ABI return data is empty or malformed");
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<TypeReference<Type>> outputs = (List) List.of(output);
        List<Type> decoded = FunctionReturnDecoder.decode(data, outputs);
        if (decoded.isEmpty()) {
            throw new IllegalArgumentException("ABI return data is empty");
        }
        return decoded;
    }
}
