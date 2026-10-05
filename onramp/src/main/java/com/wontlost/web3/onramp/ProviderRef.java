package com.wontlost.web3.onramp;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * 可序列化的服务商引用：序列化前直接持有实例；反序列化后实例为空（凭据是 transient 的，不能随会话写出），
 * 此时按名称从 {@link OnrampProviders} 重新解析，避免拿到密钥为 null 的"僵尸"服务商。
 */
final class ProviderRef implements Serializable {
    private static final long serialVersionUID = 1L;
    private transient OnrampProvider provider;
    private final String name;

    ProviderRef(OnrampProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.name = provider.name();
        OnrampProviders.registerInCurrentContext(provider);
    }

    String name() { return name; }

    Optional<OnrampProvider> resolve() {
        if (provider == null) provider = OnrampProviders.findInCurrentContext(name).orElse(null);
        return Optional.ofNullable(provider);
    }
}
