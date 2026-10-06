package com.wontlost.web3.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.web3j.crypto.Hash;

/** Filter criteria for an Ethereum {@code eth_getLogs} request. */
public record LogFilter(long fromBlock, long toBlock, List<String> addresses, List<List<String>> topics) {
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern TOPIC = Pattern.compile("0x[0-9a-fA-F]{64}");
    private static final String TRANSFER_TOPIC = Hash.sha3String("Transfer(address,address,uint256)")
            .toLowerCase(Locale.ROOT);

    public LogFilter {
        if (fromBlock < 0 || toBlock < 0 || fromBlock > toBlock) {
            throw new IllegalArgumentException("Block range must be non-negative and fromBlock must not exceed toBlock");
        }
        if (addresses != null) {
            List<String> normalized = new ArrayList<>(addresses.size());
            for (String address : addresses) {
                if (address == null || !ADDRESS.matcher(address).matches()) {
                    throw new IllegalArgumentException("Address must be a 20-byte 0x-prefixed hex value");
                }
                normalized.add(address.toLowerCase(Locale.ROOT));
            }
            addresses = List.copyOf(normalized);
        }
        if (topics != null) {
            List<List<String>> normalized = new ArrayList<>(topics.size());
            for (List<String> position : topics) {
                if (position == null) {
                    normalized.add(null);
                    continue;
                }
                List<String> alternatives = new ArrayList<>(position.size());
                for (String topic : position) {
                    if (topic == null || !TOPIC.matcher(topic).matches()) {
                        throw new IllegalArgumentException("Topic must be a 32-byte 0x-prefixed hex value");
                    }
                    alternatives.add(topic.toLowerCase(Locale.ROOT));
                }
                normalized.add(List.copyOf(alternatives));
            }
            topics = Collections.unmodifiableList(normalized);
        }
    }

    /** Creates an ERC-20 Transfer filter optionally restricted to one recipient. */
    public static LogFilter erc20Transfers(long from, long to, String token, String recipient) {
        if (token == null || !ADDRESS.matcher(token).matches()) {
            throw new IllegalArgumentException("Token must be a 20-byte 0x-prefixed hex value");
        }
        List<List<String>> topics = new ArrayList<>();
        topics.add(List.of(TRANSFER_TOPIC));
        topics.add(null);
        if (recipient == null) {
            topics.add(null);
        } else {
            if (!ADDRESS.matcher(recipient).matches()) {
                throw new IllegalArgumentException("Recipient must be a 20-byte 0x-prefixed hex value");
            }
            topics.add(List.of("0x" + "0".repeat(24) + recipient.substring(2).toLowerCase(Locale.ROOT)));
        }
        return new LogFilter(from, to, List.of(token), topics);
    }
}
