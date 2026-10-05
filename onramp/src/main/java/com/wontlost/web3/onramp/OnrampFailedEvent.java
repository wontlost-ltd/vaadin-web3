package com.wontlost.web3.onramp;

import com.vaadin.flow.component.ComponentEvent;

/** Fired when a provider cannot create a checkout session. */
public final class OnrampFailedEvent extends ComponentEvent<FiatOnrampButton> {
    private final OnrampException exception;
    /** Creates the failed-session event. */
    public OnrampFailedEvent(FiatOnrampButton source, OnrampException exception) { super(source, false); this.exception = exception; }
    /** Returns the provider failure. */
    public OnrampException getException() { return exception; }
}
