package com.wontlost.web3.chain;

import java.util.List;

/** A log returned by an Ethereum node. */
public record EthLog(String address, List<String> topics, String data, String transactionHash,
                     long transactionIndex, long blockNumber, String blockHash, long logIndex,
                     boolean removed) { }
