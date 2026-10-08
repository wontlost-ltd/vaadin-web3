package com.wontlost.web3.siws;

/** SIWS 验证失败，携带对外稳定的失败码（不含内部细节）。 */
public final class SiwsException extends RuntimeException {
    private final String code;

    public SiwsException(String code) {
        super(code, null, false, false);
        this.code = code;
    }

    /** 稳定失败码，如 {@code siws_message_mismatch}、{@code siws_invalid_signature}。 */
    public String code() {
        return code;
    }
}
