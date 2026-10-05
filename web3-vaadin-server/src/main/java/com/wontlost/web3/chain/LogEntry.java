package com.wontlost.web3.chain;

import java.util.List;
import java.util.Locale;

/** An EVM event log. */
public record LogEntry(String address, List<String> topics, String data, long logIndex) {
    public LogEntry {
        address = address.toLowerCase(Locale.ROOT);
        topics = List.copyOf(topics);
    }
}
