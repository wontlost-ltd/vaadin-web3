package com.wontlost.web3.chain;

import java.util.List;
import java.util.Locale;

/** Receipt of a mined EVM transaction. */
public record TransactionReceipt(String transactionHash, long blockNumber, boolean status,
        String from, String to, List<LogEntry> logs, String blockHash) {
    public TransactionReceipt(String transactionHash, long blockNumber, boolean status,
            String from, String to, List<LogEntry> logs) {
        this(transactionHash, blockNumber, status, from, to, logs, null);
    }
    public TransactionReceipt {
        from = from == null ? null : from.toLowerCase(Locale.ROOT);
        to = to == null ? null : to.toLowerCase(Locale.ROOT);
        logs = List.copyOf(logs);
    }
}
