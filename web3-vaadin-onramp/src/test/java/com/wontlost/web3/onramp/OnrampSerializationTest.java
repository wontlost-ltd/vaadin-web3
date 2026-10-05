package com.wontlost.web3.onramp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.server.VaadinContext;
import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.chain.Tokens;
import com.wontlost.web3.pay.OnrampAction;

/** 凭据不随会话序列化：反序列化后的结账动作必须按名称从注册表找回服务商，而不是拿到密钥为 null 的实例。 */
class OnrampSerializationTest {
    private final Supplier<VaadinContext> originalLookup = OnrampProviders.contextLookup;

    @AfterEach void restoreLookup() {
        OnrampProviders.contextLookup = originalLookup;
    }

    @Test void deserializedCheckoutActionResolvesTheRegisteredProvider() throws Exception {
        CredentialProvider original = new CredentialProvider("secret");
        OnrampAction action = FiatOnrampButton.forCheckout(original);

        OnrampAction restored = roundTrip(action);
        ProviderRef ref = roundTrip(new ProviderRef(original));
        CredentialProvider afterRestart = new CredentialProvider("secret");
        VaadinContext context = context();
        OnrampProviders.register(context, afterRestart);
        OnrampProviders.contextLookup = () -> context;

        TokenInfo usdc = Tokens.usdc(1).orElseThrow();
        // 不能抛 NPE：服务商按名称解析为启动时注册的新实例
        assertNotNull(restored.create(null, usdc, BigDecimal.ONE));
        assertSame(afterRestart, ref.resolve().orElseThrow());
        assertEquals("secret", afterRestart.secret);
    }

    @Test void deserializedActionWithoutRegistrationShowsNothingInsteadOfFailing() throws Exception {
        OnrampAction restored = roundTrip(FiatOnrampButton.forCheckout(new CredentialProvider("secret")));
        OnrampProviders.contextLookup = () -> null;

        assertNull(restored.create(null, Tokens.usdc(1).orElseThrow(), BigDecimal.ONE));
    }

    @Test void credentialsAreNotWrittenToTheSerializedForm() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(FiatOnrampButton.forCheckout(new MoonPayOnramp("pk_test_abc", "sk_test_TOPSECRET")));
        }
        assertEquals(-1, new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("TOPSECRET"));
    }

    @Test void providerOutageHidesTheButtonInsteadOfBreakingTheCheckout() {
        OnrampProvider down = new CredentialProvider("secret") {
            @Override public boolean supports(TokenInfo token) { throw new OnrampException("Credential Provider", 503, "catalog down"); }
        };
        OnrampAction action = FiatOnrampButton.forCheckout(down);
        // 目录接口故障时不抛出，只是不显示按钮
        assertNull(action.create(null, Tokens.usdc(1).orElseThrow(), BigDecimal.ONE));
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private static VaadinContext context() { return OnrampSerializationTestSupport.context(); }

    /** 与真实服务商相同的序列化约定：凭据字段为 transient。 */
    private static class CredentialProvider implements OnrampProvider {
        private static final long serialVersionUID = 1L;
        private final transient String secret;
        CredentialProvider(String secret) { this.secret = secret; }
        public String name() { return "Credential Provider"; }
        public boolean supports(TokenInfo token) { return secret.length() > 0; }
        public URI createSession(OnrampOrder order) { return URI.create("https://provider.test/" + secret.length()); }
        public boolean isTestEnvironment() { return true; }
    }
}
