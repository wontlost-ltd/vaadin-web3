package com.wontlost.web3.monitor.service.security;

import java.util.Locale;
import java.util.regex.Pattern;
import org.web3j.crypto.Keys;

public final class AddressValidator {
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private AddressValidator() { }
    public static String checksum(String value) {
        if (value == null || !ADDRESS.matcher(value).matches()) throw new IllegalArgumentException("Address must be a 20-byte EVM address");
        String checksum = Keys.toChecksumAddress(value);
        String body = value.substring(2);
        if (!body.equals(body.toLowerCase(Locale.ROOT)) && !body.equals(body.toUpperCase(Locale.ROOT)) && !checksum.equals(value)) {
            throw new IllegalArgumentException("Address has an invalid EIP-55 checksum");
        }
        return checksum;
    }
}
