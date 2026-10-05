package com.wontlost.web3;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.DomEvent;
import com.vaadin.flow.component.EventData;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.shared.Registration;

/**
 * Displays an Ethereum address with a deterministic color badge,
 * abbreviated by default ({@code 0x1234…abcd}), optionally click-to-copy.
 *
 * <pre>{@code
 * Web3Address address = new Web3Address("0x1234...", true);
 * add(address);
 * }</pre>
 */
@Tag("web3-address")
@JsModule("./web3-address.js")
@NpmPackage(value = "lit", version = "^3.0.0")
public class Web3Address extends Component {

    public Web3Address() {
    }

    public Web3Address(String address) {
        setAddress(address);
    }

    public Web3Address(String address, boolean copyable) {
        setAddress(address);
        setCopyable(copyable);
    }

    public void setAddress(String address) {
        getElement().setProperty("address", address == null ? "" : address);
    }

    public String getAddress() {
        return getElement().getProperty("address", "");
    }

    /** When {@code true}, shows the full address instead of the abbreviation. */
    public void setFull(boolean full) {
        getElement().setProperty("full", full);
    }

    public boolean isFull() {
        return getElement().getProperty("full", false);
    }

    /** When {@code true}, clicking the address copies it to the clipboard. */
    public void setCopyable(boolean copyable) {
        getElement().setProperty("copyable", copyable);
    }

    public boolean isCopyable() {
        return getElement().getProperty("copyable", false);
    }

    /** Fired after the address was copied to the clipboard. */
    public Registration addCopiedListener(ComponentEventListener<AddressCopiedEvent> listener) {
        return addListener(AddressCopiedEvent.class, listener);
    }

    /** Event fired when the address has been copied. */
    @DomEvent("web3-address-copied")
    public static class AddressCopiedEvent extends ComponentEvent<Web3Address> {
        private final String address;

        public AddressCopiedEvent(Web3Address source, boolean fromClient,
                @EventData("event.detail.address") String address) {
            super(source, fromClient);
            this.address = address;
        }

        public String getAddress() {
            return address;
        }
    }
}
