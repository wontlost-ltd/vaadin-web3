package com.wontlost.web3.chain;

import java.util.Objects;

/** {@link JsonRpcDialect#of} 的实现：分类结果为 null 时立即失败，避免在熔断逻辑中才出现空指针。 */
record BasicJsonRpcDialect(String healthProbeMethod, JsonRpcDialect.Classifier classifier) implements JsonRpcDialect {
    BasicJsonRpcDialect {
        Objects.requireNonNull(healthProbeMethod);
        Objects.requireNonNull(classifier);
        if (healthProbeMethod.isBlank()) throw new IllegalArgumentException("healthProbeMethod must not be blank");
    }

    @Override
    public EthRpcException.Category classify(int code, String message, String data) {
        return Objects.requireNonNull(classifier.classify(code, message, data), "classifier returned null");
    }
}
