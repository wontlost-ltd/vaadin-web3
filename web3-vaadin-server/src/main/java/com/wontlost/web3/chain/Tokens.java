package com.wontlost.web3.chain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Registry of built-in token contracts. */
public final class Tokens {
    private static final Map<String, Map<Long, TokenInfo>> REGISTRY = registry();
    private Tokens() { }

    private static Map<String, Map<Long, TokenInfo>> registry() {
        Map<String, Map<Long, TokenInfo>> tokens = new LinkedHashMap<>();
        add(tokens, "USDC", Map.ofEntries(
                Map.entry(1L, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"),
                Map.entry(11155111L, "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"),
                Map.entry(8453L, "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"),
                Map.entry(84532L, "0x036CbD53842c5426634e7929541eC2318f3dCF7e"),
                Map.entry(42161L, "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"),
                Map.entry(421614L, "0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d"),
                Map.entry(10L, "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85"),
                Map.entry(11155420L, "0x5fd84259d66Cd46123540766Be93DFE6D43130D7"),
                Map.entry(137L, "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"),
                Map.entry(80002L, "0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582"),
                Map.entry(43114L, "0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E")));
        add(tokens, "USDT", Map.of(1L, "0xdAC17F958D2ee523a2206206994597C13D831ec7",
                43114L, "0x9702230A8Ea53601f5cD2dc00fDBc13d4dF4A8c7",
                42161L, "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9",
                10L, "0x01bFF41798a0BcF287b996046Ca68b395DbC1071",
                137L, "0xc2132D05D31c914a87C6611C10748AEb04B58e8F"));
        add(tokens, "EURC", Map.of(1L, "0x1aBaEA1f7C830bD89Acc67eC4af516284b1bC33c",
                8453L, "0x60a3E35Cc302bFA44Cb288Bc5a4F316Fdb1adb42",
                43114L, "0xC891EB4cbdEFf6e073e859e987815Ed1505c2ACD",
                11155111L, "0x08210F9170F89Ab7658F0B5E3fF39b0E03C594D4",
                84532L, "0x808456652fdb597867f38412077A9182bf77359F",
                43113L, "0x5E44db7996c682E92a960b65AC713a54AD815c6B"));
        add(tokens, "PYUSD", Map.of(1L, "0x6c3ea9036406852006290770BEdFcAbA0e23A0e8",
                42161L, "0x46850aD61C2B7d64d08c9C754F45254596696984",
                137L, "0x99aF3EeA856556646C98c8B9b2548Fe815240750",
                11155111L, "0xCaC524BcA292aaade2DF8A05cC58F0a65B1B3bB9",
                421614L, "0x637A1259C6afd7E3AdF63993cA7E58BB438aB1B1",
                80002L, "0x4549bb98c667aAb626627C118102c28065E8f54C"));
        return Map.copyOf(tokens);
    }

    private static void add(Map<String, Map<Long, TokenInfo>> registry, String symbol,
            Map<Long, String> addresses) {
        Map<Long, TokenInfo> byChain = addresses.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> new TokenInfo(symbol, entry.getKey(), entry.getValue(), 6)));
        registry.put(symbol, byChain);
    }

    /** Returns the built-in USDC contract for a supported chain. */
    public static Optional<TokenInfo> usdc(long chainId) { return find("USDC", chainId); }
    /** Returns the built-in USDT contract for a supported chain. */
    public static Optional<TokenInfo> usdt(long chainId) { return find("USDT", chainId); }
    /** Returns the built-in EURC contract for a supported chain. */
    public static Optional<TokenInfo> eurc(long chainId) { return find("EURC", chainId); }
    /** Returns the built-in PYUSD contract for a supported chain. */
    public static Optional<TokenInfo> pyusd(long chainId) { return find("PYUSD", chainId); }
    /** Looks up a built-in token by case-insensitive symbol and chain id. */
    public static Optional<TokenInfo> find(String symbol, long chainId) {
        if (symbol == null) return Optional.empty();
        return Optional.ofNullable(REGISTRY.get(symbol.toUpperCase(Locale.ROOT)))
                .map(tokens -> tokens.get(chainId));
    }
    /** Returns the immutable set of built-in symbols. */
    public static Set<String> symbols() { return REGISTRY.keySet(); }
    /** Returns the immutable set of chains registering the given symbol. */
    public static Set<Long> chains(String symbol) {
        Map<Long, TokenInfo> tokens = registryFor(symbol);
        return tokens.keySet();
    }
    /** Returns the ISO currency used to denominate a built-in token. */
    public static String currency(String symbol) {
        return switch (normalize(symbol)) {
            case "USDC", "USDT", "PYUSD" -> "USD";
            case "EURC" -> "EUR";
            default -> throw new IllegalArgumentException("Unknown token symbol: " + symbol);
        };
    }
    private static Map<Long, TokenInfo> registryFor(String symbol) {
        Map<Long, TokenInfo> tokens = REGISTRY.get(normalize(symbol));
        if (tokens == null) throw new IllegalArgumentException("Unknown token symbol: " + symbol);
        return tokens;
    }
    private static String normalize(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT);
    }
    /** Converts a decimal token amount to exact integer base units. */
    public static BigInteger toBaseUnits(BigDecimal amount, int decimals) {
        if (amount == null || decimals < 0 || amount.signum() < 0) throw new IllegalArgumentException("amount must be non-negative and decimals must be non-negative");
        try { return amount.movePointRight(decimals).toBigIntegerExact(); }
        catch (ArithmeticException exception) { throw new IllegalArgumentException("Amount exceeds token precision", exception); }
    }
    /** Converts integer token base units to a decimal display amount. */
    public static BigDecimal fromBaseUnits(BigInteger amount, int decimals) {
        if (amount == null || decimals < 0) throw new IllegalArgumentException("amount and non-negative decimals are required");
        return new BigDecimal(amount, decimals);
    }
}
