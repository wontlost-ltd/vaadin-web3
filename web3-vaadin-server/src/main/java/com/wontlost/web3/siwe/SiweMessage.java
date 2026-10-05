package com.wontlost.web3.siwe;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.web3j.crypto.Keys;

/** Immutable EIP-4361 message with strict parsing and canonical LF formatting. */
public final class SiweMessage implements Serializable {

    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9]{8,}");
    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Pattern DOMAIN_LINE = Pattern.compile("(?:([A-Za-z][A-Za-z0-9+.-]*)://)?(.+) wants you to sign in with your Ethereum account:");

    private final String scheme;
    private final String domain;
    private final String address;
    private final String statement;
    private final boolean emptyStatementLine;
    private final String uri;
    private final String version;
    private final long chainId;
    private final String nonce;
    private final Instant issuedAt;
    private final Instant expirationTime;
    private final Instant notBefore;
    private final String issuedAtText;
    private final String expirationTimeText;
    private final String notBeforeText;
    private final String requestId;
    private final List<String> resources;

    private SiweMessage(Builder builder) {
        scheme = builder.scheme;
        domain = requireText(builder.domain, "domain");
        try {
            if (builder.address == null || !ADDRESS.matcher(builder.address).matches()) {
                throw new IllegalArgumentException("address must be a 20-byte 0x-prefixed hexadecimal value");
            }
            address = Keys.toChecksumAddress(Objects.requireNonNull(builder.address, "address"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("address must be a valid Ethereum address", exception);
        }
        statement = builder.statement;
        emptyStatementLine = builder.emptyStatementLine;
        if (statement != null && (statement.contains("\n") || statement.contains("\r"))) {
            throw new IllegalArgumentException("statement must not contain line breaks");
        }
        if ("".equals(statement)) {
            throw new IllegalArgumentException("statement must not be empty when provided");
        }
        uri = requireText(builder.uri, "uri");
        version = Objects.requireNonNull(builder.version, "version");
        if (!"1".equals(version)) {
            throw new IllegalArgumentException("version must be 1");
        }
        if (builder.chainId <= 0) {
            throw new IllegalArgumentException("chainId must be greater than zero");
        }
        chainId = builder.chainId;
        nonce = Objects.requireNonNull(builder.nonce, "nonce");
        if (!NONCE.matcher(nonce).matches()) {
            throw new IllegalArgumentException("nonce must contain at least 8 alphanumeric characters");
        }
        issuedAt = Objects.requireNonNull(builder.issuedAt, "issuedAt");
        expirationTime = builder.expirationTime;
        notBefore = builder.notBefore;
        issuedAtText = Objects.requireNonNullElse(builder.issuedAtText, issuedAt.toString());
        expirationTimeText = expirationTime == null ? null
                : Objects.requireNonNullElse(builder.expirationTimeText, expirationTime.toString());
        notBeforeText = notBefore == null ? null
                : Objects.requireNonNullElse(builder.notBeforeText, notBefore.toString());
        requestId = builder.requestId;
        resources = List.copyOf(builder.resources);
        if (scheme != null && !scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            throw new IllegalArgumentException("scheme is invalid");
        }
        checkOptionalLine(issuedAtText, "issuedAt");
        checkOptionalLine(expirationTimeText, "expirationTime");
        checkOptionalLine(notBeforeText, "notBefore");
        checkOptionalLine(requestId, "requestId");
        resources.forEach(resource -> checkOptionalLine(requireText(resource, "resource"), "resource"));
    }

    private static void checkOptionalLine(String value, String name) {
        if (value != null && (value.contains("\n") || value.contains("\r"))) {
            throw new IllegalArgumentException(name + " must not contain line breaks");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(name + " must be non-empty and contain no line breaks");
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Parses a canonical EIP-4361 message. */
    public static SiweMessage parse(String message) {
        try {
            return parseStrict(message);
        } catch (SiweException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SiweException(SiweException.Reason.MALFORMED, "Malformed SIWE message", exception);
        }
    }

    private static SiweMessage parseStrict(String message) {
        if (message == null || message.contains("\r")) {
            throw malformed();
        }
        String[] lines = message.split("\n", -1);
        if (lines.length < 8 || !lines[2].isEmpty()) {
            throw malformed();
        }
        Matcher domainMatcher = DOMAIN_LINE.matcher(lines[0]);
        if (!domainMatcher.matches()) {
            throw malformed();
        }
        String scheme = domainMatcher.group(1);
        String domain = domainMatcher.group(2);
        Builder builder = builder();
        int index = 3;
        String statement = null;
        boolean uriWithoutStatement = lines[index].startsWith("URI: ")
                && index + 1 < lines.length && "Version: 1".equals(lines[index + 1]);
        if (lines[index].isEmpty() && index + 1 < lines.length && lines[index + 1].startsWith("URI: ")) {
            index++;
            uriWithoutStatement = true;
            builder.emptyStatementLine(true);
        }
        if (!uriWithoutStatement) {
            statement = lines[index++];
            if (index >= lines.length || !lines[index++].isEmpty()) {
                throw malformed();
            }
        }
        if (index >= lines.length || !lines[index++].startsWith("URI: ")) {
            throw malformed();
        }
        String uri = lines[index - 1].substring(5);
        if (index >= lines.length || !"Version: 1".equals(lines[index++])) {
            throw malformed();
        }
        if (index >= lines.length || !lines[index].startsWith("Chain ID: ")) {
            throw malformed();
        }
        long chainId = Long.parseLong(lines[index++].substring(10));
        if (index >= lines.length || !lines[index].startsWith("Nonce: ")) {
            throw malformed();
        }
        String nonce = lines[index++].substring(7);
        if (index >= lines.length || !lines[index].startsWith("Issued At: ")) {
            throw malformed();
        }
        String issuedAtText = lines[index++].substring(11);
        Instant issuedAt = parseTimestamp(issuedAtText);
        builder.scheme(scheme).domain(domain).address(lines[1]).statement(statement)
                .uri(uri).chainId(chainId).nonce(nonce).issuedAt(issuedAt).issuedAtText(issuedAtText);
        if (index < lines.length && lines[index].startsWith("Expiration Time: ")) {
            String value = lines[index++].substring(17);
            builder.expirationTime(parseTimestamp(value)).expirationTimeText(value);
        }
        if (index < lines.length && lines[index].startsWith("Not Before: ")) {
            String value = lines[index++].substring(12);
            builder.notBefore(parseTimestamp(value)).notBeforeText(value);
        }
        if (index < lines.length && lines[index].startsWith("Request ID: ")) {
            builder.requestId(lines[index++].substring(12));
        }
        if (index < lines.length && "Resources:".equals(lines[index])) {
            index++;
            if (index == lines.length) {
                throw malformed();
            }
            while (index < lines.length && lines[index].startsWith("- ")) {
                builder.resource(lines[index++].substring(2));
            }
        }
        if (index != lines.length) {
            throw malformed();
        }
        SiweMessage parsed = builder.build();
        if (!parsed.toMessage().equals(message)) {
            throw malformed();
        }
        return parsed;
    }

    private static Instant parseTimestamp(String value) {
        return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
    }

    private static SiweException malformed() {
        return new SiweException(SiweException.Reason.MALFORMED, "Malformed SIWE message");
    }

    public String toMessage() {
        StringBuilder text = new StringBuilder();
        if (scheme != null) {
            text.append(scheme).append("://");
        }
        text.append(domain).append(" wants you to sign in with your Ethereum account:\n")
                .append(address).append("\n\n");
        if (statement != null) {
            text.append(statement).append("\n\n");
        } else if (emptyStatementLine) {
            text.append('\n');
        }
        text.append("URI: ").append(uri)
                .append("\nVersion: 1\nChain ID: ").append(chainId)
                .append("\nNonce: ").append(nonce)
                .append("\nIssued At: ").append(issuedAtText);
        if (expirationTime != null) {
            text.append("\nExpiration Time: ").append(expirationTimeText);
        }
        if (notBefore != null) {
            text.append("\nNot Before: ").append(notBeforeText);
        }
        if (requestId != null) {
            text.append("\nRequest ID: ").append(requestId);
        }
        if (!resources.isEmpty()) {
            text.append("\nResources:");
            resources.forEach(resource -> text.append("\n- ").append(resource));
        }
        return text.toString();
    }

    /** Returns the optional URI scheme. */
    public String getScheme() { return scheme; }
    /** Returns the domain. */
    public String getDomain() { return domain; }
    /** Returns the EIP-55 checksummed address. */
    public String getAddress() { return address; }
    /** Returns the optional statement. */
    public String getStatement() { return statement; }
    /** Returns the request URI. */
    public String getUri() { return uri; }
    /** Returns the protocol version. */
    public String getVersion() { return version; }
    /** Returns the EVM chain id. */
    public long getChainId() { return chainId; }
    /** Returns the one-time nonce. */
    public String getNonce() { return nonce; }
    /** Returns the issue timestamp. */
    public Instant getIssuedAt() { return issuedAt; }
    /** Returns the optional expiration timestamp. */
    public Instant getExpirationTime() { return expirationTime; }
    /** Returns the optional not-before timestamp. */
    public Instant getNotBefore() { return notBefore; }
    /** Returns the optional request identifier. */
    public String getRequestId() { return requestId; }
    /** Returns the immutable resource list. */
    public List<String> getResources() { return resources; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SiweMessage that)) return false;
        return chainId == that.chainId && Objects.equals(scheme, that.scheme)
                && domain.equals(that.domain) && address.equals(that.address)
                && Objects.equals(statement, that.statement) && emptyStatementLine == that.emptyStatementLine
                && uri.equals(that.uri)
                && version.equals(that.version) && nonce.equals(that.nonce)
                && issuedAtText.equals(that.issuedAtText) && Objects.equals(expirationTimeText, that.expirationTimeText)
                && Objects.equals(notBeforeText, that.notBeforeText) && Objects.equals(requestId, that.requestId)
                && resources.equals(that.resources);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, domain, address, statement, emptyStatementLine, uri, version, chainId, nonce,
                issuedAtText, expirationTimeText, notBeforeText, requestId, resources);
    }

    /** Builder for immutable SIWE messages. */
    public static final class Builder {
        private String scheme;
        private String domain;
        private String address;
        private String statement;
        private boolean emptyStatementLine;
        private String uri;
        private String version = "1";
        private long chainId;
        private String nonce;
        private Instant issuedAt;
        private Instant expirationTime;
        private Instant notBefore;
        private String issuedAtText;
        private String expirationTimeText;
        private String notBeforeText;
        private String requestId;
        private final List<String> resources = new ArrayList<>();

        /** Sets the optional URI scheme. */
        public Builder scheme(String value) { scheme = value; return this; }
        /** Sets the domain. */
        public Builder domain(String value) { domain = value; return this; }
        /** Sets the Ethereum account address. */
        public Builder address(String value) { address = value; return this; }
        /** Sets the optional single-line statement. */
        public Builder statement(String value) { statement = value; return this; }
        Builder emptyStatementLine(boolean value) { emptyStatementLine = value; return this; }
        /** Sets the request URI. */
        public Builder uri(String value) { uri = value; return this; }
        /** Sets the message version, which must be {@code 1}. */
        public Builder version(String value) { version = value; return this; }
        /** Sets the positive EVM chain id. */
        public Builder chainId(long value) { chainId = value; return this; }
        /** Sets the alphanumeric nonce. */
        public Builder nonce(String value) { nonce = value; return this; }
        /** Sets the issued-at instant. */
        public Builder issuedAt(Instant value) { issuedAt = value; issuedAtText = value == null ? null : value.toString(); return this; }
        /** Sets the optional expiration instant. */
        public Builder expirationTime(Instant value) { expirationTime = value; expirationTimeText = value == null ? null : value.toString(); return this; }
        /** Sets the optional not-before instant. */
        public Builder notBefore(Instant value) { notBefore = value; notBeforeText = value == null ? null : value.toString(); return this; }
        Builder issuedAtText(String value) { issuedAtText = value; return this; }
        Builder expirationTimeText(String value) { expirationTimeText = value; return this; }
        Builder notBeforeText(String value) { notBeforeText = value; return this; }
        /** Sets the optional request identifier. */
        public Builder requestId(String value) { requestId = value; return this; }
        /** Replaces the optional resource list. */
        public Builder resources(List<String> value) { resources.clear(); resources.addAll(value); return this; }
        /** Appends one resource URI. */
        public Builder resource(String value) { resources.add(value); return this; }
        /** Validates the supplied fields and creates an immutable message. */
        public SiweMessage build() { return new SiweMessage(this); }
    }
}
