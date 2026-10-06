package com.wontlost.web3.demo;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.Component;
import com.wontlost.web3.NetworkIndicator;
import com.wontlost.web3.ui.Balance;
import com.wontlost.web3.ui.TransactionStatus;

class TransactionsViewTest {
    @Test
    void routeContainsNetworkBalanceAndTransactionComponents() {
        TransactionsView view = new TransactionsView(31337);
        assertTrue(contains(view, NetworkIndicator.class));
        assertTrue(contains(view, Balance.class));
        assertTrue(contains(view, TransactionStatus.class));
    }

    private static boolean contains(Component root, Class<?> type) {
        return type.isInstance(root) || root.getChildren().anyMatch(child -> contains(child, type));
    }
}
