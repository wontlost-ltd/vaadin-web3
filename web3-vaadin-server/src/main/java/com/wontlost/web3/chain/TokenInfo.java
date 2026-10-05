package com.wontlost.web3.chain;

/** Metadata for a supported fungible token. */
public record TokenInfo(String symbol, long chainId, String address, int decimals) { }
