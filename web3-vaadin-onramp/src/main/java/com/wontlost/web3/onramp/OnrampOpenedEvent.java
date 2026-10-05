package com.wontlost.web3.onramp;

import java.net.URI;

import com.vaadin.flow.component.ComponentEvent;

/** Fired after the browser accepts a provider URL in a new window. */
public final class OnrampOpenedEvent extends ComponentEvent<FiatOnrampButton> {
    private final URI uri;
    /** Creates the opened-session event. */
    public OnrampOpenedEvent(FiatOnrampButton source, URI uri) { super(source, false); this.uri = uri; }
    /** Returns the opened checkout URL. */
    public URI getUri() { return uri; }
}
