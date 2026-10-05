package com.wontlost.web3.pay;

import java.io.Serializable;
import java.math.BigDecimal;

import com.vaadin.flow.component.Component;
import com.wontlost.web3.chain.TokenInfo;

/** Creates an optional card-purchase component for the current checkout choice. */
@FunctionalInterface
public interface OnrampAction extends Serializable {
    /** Creates the action component, or returns {@code null} when no action should be shown. */
    Component create(StablecoinCheckout checkout, TokenInfo token, BigDecimal amount);
}
