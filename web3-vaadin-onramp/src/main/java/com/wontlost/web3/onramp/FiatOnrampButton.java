package com.wontlost.web3.onramp;

import java.net.URI;
import java.util.Objects;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.function.SerializableSupplier;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.shared.Registration;
import com.wontlost.web3.chain.TokenInfo;
import com.wontlost.web3.pay.OnrampAction;
import com.wontlost.web3.pay.StablecoinCheckout;
import com.wontlost.web3.siwe.Web3Session;

/** Opens a provider's hosted card purchase flow from a Vaadin server-side button.
 * <p>{@link OnrampProvider#createSession(OnrampOrder)} makes synchronous provider requests from the click callback,
 * each bounded by the default ten-second request timeout. Transak may refresh its cached access token before creating
 * the session. Keep all provider keys on the server.
 * <p>The client IP sent to providers (required by Transak) comes from {@code VaadinRequest#getRemoteAddr()}. Behind a
 * reverse proxy, configure your servlet container to trust the proxy's forwarding headers (for Spring Boot,
 * {@code server.forward-headers-strategy=native}); this class deliberately does not parse forwarding headers itself.
 * <p>Use a SIWE-verified address from {@link Web3Session#current()} where available. A client-reported connected
 * wallet address is also suitable because it only determines where the user sends their own purchase.
 * <p>Provider credentials are never serialized with the session: the button keeps only the provider name and, after
 * deserialization, resolves the provider again from {@link OnrampProviders}. Register providers there at startup when
 * sessions may be persisted or replicated.
 */
public final class FiatOnrampButton extends Composite<VerticalLayout> {
    private final ProviderRef provider;
    private final SerializableSupplier<OnrampOrder> orderSupplier;
    private final String providerName;
    private final Button button = new Button();
    private final Anchor fallback = new Anchor();

    /** Creates a purchase button for orders supplied at click time. */
    public FiatOnrampButton(OnrampProvider provider, SerializableSupplier<OnrampOrder> orderSupplier) {
        this(new ProviderRef(provider), orderSupplier);
    }

    private FiatOnrampButton(ProviderRef provider, SerializableSupplier<OnrampOrder> orderSupplier) {
        this.provider = Objects.requireNonNull(provider);
        this.providerName = provider.name();
        this.orderSupplier = Objects.requireNonNull(orderSupplier);
        button.setText("Buy with card");
        fallback.setTarget("_blank");
        fallback.getElement().setAttribute("rel", "noopener");
        fallback.setVisible(false);
        button.addClickListener(event -> createSession());
        getContent().add(button, fallback);
    }

    /** Sets the button label. */
    public FiatOnrampButton setText(String text) { button.setText(Objects.requireNonNull(text)); return this; }
    /** Registers for successfully opened checkout sessions. */
    public Registration addOnrampOpenedListener(com.vaadin.flow.component.ComponentEventListener<OnrampOpenedEvent> listener) {
        return addListener(OnrampOpenedEvent.class, listener);
    }
    /** Registers for checkout session errors. */
    public Registration addOnrampFailedListener(com.vaadin.flow.component.ComponentEventListener<OnrampFailedEvent> listener) {
        return addListener(OnrampFailedEvent.class, listener);
    }

    void createSession() {
        OnrampProvider resolved = provider.resolve().orElse(null);
        if (resolved == null) {
            fireEvent(new OnrampFailedEvent(this, new OnrampException(providerName, -1,
                    "No on-ramp provider named " + providerName + " is registered; call OnrampProviders.register at startup")));
            return;
        }
        OnrampOrder order;
        URI uri;
        try {
            order = orderSupplier.get();
            if (order == null) return;
            button.setEnabled(false);
            fallback.setVisible(false);
            uri = resolved.createSession(order);
            if (!uri.isAbsolute() || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
                throw new IllegalArgumentException("Provider URL must be an absolute HTTP URL");
        } catch (RuntimeException failure) {
            OnrampException exception = failure instanceof OnrampException onramp ? onramp
                    : new OnrampException(providerName, failure.getMessage(), failure);
            fireEvent(new OnrampFailedEvent(this, exception));
            button.setEnabled(true);
            return;
        }
        // 不能用 'noopener' 特性：按 HTML 规范此时 window.open 恒返回 null，会把成功打开误判为被拦截。
        // 改为打开后手动置空 opener，安全效果相同（新页面无法反向操作本页）。
        getElement().executeJs("const w = window.open($0, '_blank'); if (w) { w.opener = null; } return !!w;", uri.toString())
                .then(Boolean.class, opened -> {
                    handlePopupResult(uri, Boolean.TRUE.equals(opened));
                }, error -> {
                    handlePopupResult(uri, false);
                });
    }


    void handlePopupResult(URI uri, boolean opened) {
        if (opened) fireEvent(new OnrampOpenedEvent(this, uri));
        else {
            fallback.getElement().setAttribute("href", uri.toString());
            fallback.setText("Continue to " + providerName);
            fallback.setVisible(true);
        }
        button.setEnabled(true);
    }

    Anchor fallbackLink() { return fallback; }

    /**
     * 入金是可选的辅助入口：界面构建时只读缓存的目录（未加载时后台加载并暂不显示），
     * 目录不可用时隐藏按钮——绝不能让结账页等待网络或因此打不开。
     */
    private static boolean supportsQuietly(OnrampProvider provider, TokenInfo token) {
        try {
            return provider.supportsIfLoaded(token).orElse(false);
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(FiatOnrampButton.class)
                    .warn("{} catalog unavailable; hiding the buy-with-card button", provider.name(), unavailable);
            return false;
        }
    }

    /** Creates the checkout's buy-with-card action, using verified or connected wallet context. */
    public static OnrampAction forCheckout(OnrampProvider provider) {
        // 动作随结账组件进入会话：只捕获可序列化的 ProviderRef，反序列化后按名称重新解析服务商
        ProviderRef ref = new ProviderRef(provider);
        return (checkout, token, amount) -> {
            OnrampProvider resolved = ref.resolve().orElse(null);
            if (resolved == null || !supportsQuietly(resolved, token)) return null;
            FiatOnrampButton button = new FiatOnrampButton(ref, () -> {
                String address = Web3Session.current().map(signIn -> signIn.address()).orElse(checkout.getConnectedAccount());
                if (address == null || address.isBlank()) {
                    Notification.show("Connect your wallet first");
                    return null;
                }
                String ip = VaadinService.getCurrentRequest() == null ? null : VaadinService.getCurrentRequest().getRemoteAddr();
                return new OnrampOrder(token, address, amount, null, null, null, ip, checkout.getOrderId());
            });
            button.setText("Need " + token.symbol() + "? Buy with card");
            return button;
        };
    }

}
