package com.wontlost.web3.x402.payment;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.router.QueryParameters;
import com.wontlost.web3.siwe.Web3Session;

public final class PaymentGate implements BeforeEnterListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentGate.class);
    private final X402PaymentService payments;
    private final Supplier<String> identityAddress;

    public PaymentGate(X402PaymentService payments) {
        this(payments, () -> Web3Session.current().map(signIn -> signIn.address()).orElse(null));
    }

    PaymentGate(X402PaymentService payments, Supplier<String> identityAddress) {
        this.payments = java.util.Objects.requireNonNull(payments);
        this.identityAddress = java.util.Objects.requireNonNull(identityAddress);
    }

    @Override public void beforeEnter(BeforeEnterEvent event) {
        RequiresPayment required = event.getNavigationTarget().getAnnotation(RequiresPayment.class);
        if (required == null) {
            return;
        }
        // 授权身份只读取已验证的 SIWE 会话，不接受导航参数或浏览器账户。
        String address = identityAddress.get();
        String currentPath = event.getLocation().getPath();
        String query = event.getLocation().getQueryParameters().getQueryString();
        NavigationDecision decision = evaluate(required, address, currentPath, query);
        if (decision.allowed()) {
            return;
        }
        Map<String, String> parameters = new HashMap<>();
        parameters.put("resource", decision.resourceId());
        parameters.put("continue", decision.continueTarget());
        if (decision.failureCode() != null) parameters.put("error", decision.failureCode());
        event.forwardTo(decision.route(), QueryParameters.simple(parameters));
    }

    NavigationDecision evaluate(RequiresPayment required, String address, String path, String query) {
        if (required == null) {
            return new NavigationDecision(true, null, null, null, null);
        }
        if (address != null) {
            try {
                if (payments.hasAccess(required.resourceId(), address) == AccessDecision.ALLOW) {
                    return new NavigationDecision(true, null, required.resourceId(), null, null);
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("x402 access check failed code={} paymentId={} exceptionType={}",
                        "access_check_failed", "none", exception.getClass().getName());
                return denied(required, path, query, "access_check_failed");
            }
        }
        return denied(required, path, query, null);
    }

    private NavigationDecision denied(RequiresPayment required, String path, String query, String failureCode) {
        String target = path + (query == null || query.isEmpty() ? "" : "?" + query);
        if (!isSafeInternalPath(target)) {
            target = isSafeInternalPath(path) ? path : "";
        }
        return new NavigationDecision(false, required.paywallRoute(), required.resourceId(), target, failureCode);
    }

    public static boolean isSafeInternalPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.matches("(?i).*%(2f|5c).*")) {
            return false;
        }
        String decoded = value;
        try {
            for (int i = 0; i < 3; i++) {
                String next = java.net.URLDecoder.decode(decoded, java.nio.charset.StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    break;
                }
                decoded = next;
            }
            if (decoded.startsWith("//") || decoded.indexOf('\\') >= 0
                    || decoded.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                return false;
            }
            URI uri = URI.create(decoded);
            return !uri.isAbsolute() && uri.getRawAuthority() == null && uri.getFragment() == null
                    && !uri.getPath().contains("..");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    record NavigationDecision(boolean allowed, String route, String resourceId, String continueTarget,
            String failureCode) { }
}
