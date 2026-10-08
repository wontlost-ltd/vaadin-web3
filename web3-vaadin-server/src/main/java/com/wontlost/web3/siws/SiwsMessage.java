package com.wontlost.web3.siws;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sign-In With Solana 消息（Phantom SIWS 规范，与 Wallet Standard {@code solana:signIn} 的格式化规则一致）。
 * <pre>
 * ${domain} wants you to sign in with your Solana account:
 * ${address}
 *
 * ${statement}
 *
 * URI: ... / Version: ... / Chain ID: ... / Nonce: ... / Issued At: ... / Expiration Time: ... /
 * Not Before: ... / Request ID: ... / Resources:\n- ...
 * </pre>
 * 语句与各可选字段均可省略；字段按上述固定顺序出现，与前文以一个空行分隔。
 * {@link #parse} 采用严格解析：解析结果重新渲染后必须与原文逐字节一致，否则拒绝，避免同一签名对应多种语义。
 */
public record SiwsMessage(String domain, String address, String statement, String uri, String version,
        String chainId, String nonce, String issuedAt, String expirationTime, String notBefore, String requestId,
        List<String> resources) {
    private static final String HEADER = " wants you to sign in with your Solana account:";

    public SiwsMessage {
        // Wallet Standard 的 createSignInMessageText 省略空语句，此处一致地视为缺省，否则渲染出多余的空行
        if (statement != null && statement.isEmpty()) {
            statement = null;
        }
        requireText(domain, "domain");
        requireText(address, "address");
        Base58.decode(address, 32);
        resources = resources == null ? List.of() : List.copyOf(resources);
        for (String value : new String[] {domain, address, statement, uri, version, chainId, nonce, issuedAt,
                expirationTime, notBefore, requestId}) {
            if (value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("SIWS fields must not contain line breaks");
            }
        }
        for (String resource : resources) {
            requireText(resource, "resource");
            if (resource.indexOf('\n') >= 0 || resource.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("SIWS fields must not contain line breaks");
            }
        }
    }

    /** 渲染为钱包签名的消息文本。 */
    public String toMessage() {
        StringBuilder message = new StringBuilder(domain).append(HEADER).append('\n').append(address);
        if (statement != null) {
            message.append("\n\n").append(statement);
        }
        List<String> fields = new ArrayList<>();
        add(fields, "URI", uri);
        add(fields, "Version", version);
        add(fields, "Chain ID", chainId);
        add(fields, "Nonce", nonce);
        add(fields, "Issued At", issuedAt);
        add(fields, "Expiration Time", expirationTime);
        add(fields, "Not Before", notBefore);
        add(fields, "Request ID", requestId);
        if (!resources.isEmpty()) {
            fields.add("Resources:");
            resources.forEach(resource -> fields.add("- " + resource));
        }
        if (!fields.isEmpty()) {
            message.append("\n\n").append(String.join("\n", fields));
        }
        return message.toString();
    }

    /** 严格解析钱包签名的消息文本。 */
    public static SiwsMessage parse(String text) {
        Objects.requireNonNull(text, "text");
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].endsWith(HEADER)) {
            throw new IllegalArgumentException("Not a Sign-In With Solana message");
        }
        String domain = lines[0].substring(0, lines[0].length() - HEADER.length());
        String address = lines[1];
        int index = 2;
        String statement = null;
        // 空行之后若不是字段行，则为语句
        if (index + 1 < lines.length && lines[index].isEmpty() && !isFieldLine(lines[index + 1])) {
            statement = lines[index + 1];
            index += 2;
        }
        String[] values = new String[8];
        String[] labels = {"URI", "Version", "Chain ID", "Nonce", "Issued At", "Expiration Time", "Not Before",
                "Request ID"};
        List<String> resources = new ArrayList<>();
        if (index < lines.length) {
            if (!lines[index].isEmpty()) {
                throw new IllegalArgumentException("Malformed Sign-In With Solana message");
            }
            index++;
            int label = 0;
            while (index < lines.length && !lines[index].equals("Resources:")) {
                String line = lines[index];
                while (label < labels.length && !line.startsWith(labels[label] + ": ")) {
                    label++;
                }
                if (label == labels.length) {
                    throw new IllegalArgumentException("Unknown or out-of-order Sign-In With Solana field");
                }
                values[label] = line.substring(labels[label].length() + 2);
                label++;
                index++;
            }
            if (index < lines.length) {
                index++;
                while (index < lines.length) {
                    if (!lines[index].startsWith("- ")) {
                        throw new IllegalArgumentException("Malformed Sign-In With Solana resource");
                    }
                    resources.add(lines[index].substring(2));
                    index++;
                }
            }
        }
        SiwsMessage parsed = new SiwsMessage(domain, address, statement, values[0], values[1], values[2], values[3],
                values[4], values[5], values[6], values[7], resources);
        if (!parsed.toMessage().equals(text)) {
            throw new IllegalArgumentException("Non-canonical Sign-In With Solana message");
        }
        return parsed;
    }

    private static boolean isFieldLine(String line) {
        return line.startsWith("URI: ") || line.startsWith("Version: ") || line.startsWith("Chain ID: ")
                || line.startsWith("Nonce: ") || line.startsWith("Issued At: ") || line.startsWith("Expiration Time: ")
                || line.startsWith("Not Before: ") || line.startsWith("Request ID: ") || line.equals("Resources:");
    }

    private static void add(List<String> fields, String label, String value) {
        if (value != null) {
            fields.add(label + ": " + value);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("SIWS " + name + " is required");
        }
    }
}
