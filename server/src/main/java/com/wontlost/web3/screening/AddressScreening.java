package com.wontlost.web3.screening;

import java.util.Objects;

import com.vaadin.flow.server.VaadinContext;

/** Screens wallet addresses before they are accepted for sign-in or payment. */
@FunctionalInterface
public interface AddressScreening {

    /** Returns whether the address is allowed and an optional explanation. */
    ScreeningDecision screen(String address);

    /** Registers an application-scoped screening service in the Vaadin context. */
    static void register(VaadinContext context, AddressScreening screening) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(screening, "screening");
        AddressScreening current = context.getAttribute(AddressScreening.class);
        if (current != null && current != screening) {
            throw new IllegalStateException("A different AddressScreening is already registered");
        }
        context.setAttribute(AddressScreening.class, screening);
    }

    /** Finds the screening service registered for this application. */
    static AddressScreening find(VaadinContext context) {
        return context == null ? null : context.getAttribute(AddressScreening.class);
    }

    /** A screening decision with a reason when an address is blocked. */
    record ScreeningDecision(boolean allowed, String reason) {
        /** Allows the address. */
        public static ScreeningDecision allow() { return new ScreeningDecision(true, null); }
        /** Blocks the address with an explanation. */
        public static ScreeningDecision block(String reason) {
            return new ScreeningDecision(false, Objects.requireNonNull(reason, "reason"));
        }
    }
}
