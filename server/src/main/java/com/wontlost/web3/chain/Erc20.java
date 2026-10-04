package com.wontlost.web3.chain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Uint;

/** ABI helpers for common ERC-20 calls and Transfer events. */
public final class Erc20 {
    public static final String TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private Erc20() { }
    /** Encodes an ERC-20 {@code transfer} call. */
    public static String transferData(String to, BigInteger amount) {
        return FunctionEncoder.encode(new Function("transfer", List.of(new Address(to), new Uint(amount)), List.of()));
    }
    /** Encodes an ERC-20 {@code balanceOf} call. */
    public static String balanceOfData(String owner) {
        return FunctionEncoder.encode(new Function("balanceOf", List.of(new Address(owner)), List.of()));
    }
    /** Encodes an ERC-20 {@code decimals} call. */
    public static String decimalsData() {
        return FunctionEncoder.encode(new Function("decimals", List.of(), List.of()));
    }
    /** Reads the raw token balance for an owner. */
    public static BigInteger balanceOf(EthRpcClient rpc, String token, String owner) {
        return decodeUint(rpc.call(token, balanceOfData(owner), "latest"));
    }
    /** Reads the token's decimal precision. */
    public static int decimals(EthRpcClient rpc, String token) {
        return decodeUint(rpc.call(token, decimalsData(), "latest")).intValueExact();
    }
    /** Extracts Transfer events emitted by the selected token contract. */
    public static List<Transfer> transfers(TransactionReceipt receipt, String token) {
        String normalized = token.toLowerCase(Locale.ROOT);
        List<Transfer> result = new ArrayList<>();
        for (LogEntry log : receipt.logs()) {
            if (!log.address().equals(normalized) || log.topics().size() < 3
                    || !log.topics().getFirst().equalsIgnoreCase(TRANSFER_TOPIC)) continue;
            result.add(new Transfer(topicAddress(log.topics().get(1)), topicAddress(log.topics().get(2)), decodeUint(log.data())));
        }
        return List.copyOf(result);
    }
    private static String topicAddress(String topic) {
        String value = topic.startsWith("0x") ? topic.substring(2) : topic;
        return "0x" + value.substring(value.length() - 40).toLowerCase(Locale.ROOT);
    }
    private static BigInteger decodeUint(String hex) {
        String value = hex.startsWith("0x") ? hex.substring(2) : hex;
        return new BigInteger(value.isEmpty() ? "0" : value, 16);
    }
}
