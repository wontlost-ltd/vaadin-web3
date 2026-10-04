package com.wontlost.web3.chain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

/** Registry of built-in token contracts. */
public final class Tokens {
    private static final Map<Long, TokenInfo> USDC = Map.ofEntries(
            Map.entry(1L, token(1,"0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48")),
            Map.entry(11155111L, token(11155111,"0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238")),
            Map.entry(8453L, token(8453,"0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913")),
            Map.entry(84532L, token(84532,"0x036CbD53842c5426634e7929541eC2318f3dCF7e")),
            Map.entry(42161L, token(42161,"0xaf88d065e77c8cC2239327C5EDb3A432268e5831")),
            Map.entry(421614L, token(421614,"0x75faf114eafb1BDbe2F0316DF893fd58CE46AA4d")),
            Map.entry(10L, token(10,"0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85")),
            Map.entry(11155420L, token(11155420,"0x5fd84259d66Cd46123540766Be93DFE6D43130D7")),
            Map.entry(137L, token(137,"0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359")),
            Map.entry(80002L, token(80002,"0x41E94Eb019C0762f9Bfcf9Fb1E58725BfB0e7582")),
            Map.entry(43114L, token(43114,"0xB97EF9Ef8734C71904D8002F8b6Bc66Dd9c48a6E")));
    private Tokens() { }
    private static TokenInfo token(long chain, String address) { return new TokenInfo("USDC", chain, address, 6); }
    /** Returns the built-in USDC contract for a supported chain. */
    public static Optional<TokenInfo> usdc(long chainId) { return Optional.ofNullable(USDC.get(chainId)); }
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
