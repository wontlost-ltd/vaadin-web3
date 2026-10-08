package com.wontlost.web3.solana;

/** Solana JSON-RPC 调用失败；消息只含方法名与通用原因，不含节点地址或节点返回的错误文本。 */
public final class SolanaRpcException extends RuntimeException {
    /** 节点返回的错误缺少错误码时使用的 JSON-RPC 内部错误码。 */
    public static final int INTERNAL_ERROR = -32603;

    private final int code;
    private final int httpStatus;

    public SolanaRpcException(int code, String message) {
        this(code, 0, message);
    }

    public SolanaRpcException(int code, int httpStatus, String message) {
        super(message, null, false, false);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    /** 节点返回的 JSON-RPC 错误码；本地失败（传输、响应格式）时为 0。 */
    public int getCode() {
        return code;
    }

    /** 传输层失败时的 HTTP 状态码（如 429、503）；其他情况为 0。 */
    public int getHttpStatus() {
        return httpStatus;
    }
}
