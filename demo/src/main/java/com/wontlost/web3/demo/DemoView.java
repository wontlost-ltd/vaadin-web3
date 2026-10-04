package com.wontlost.web3.demo;

import java.math.BigDecimal;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;

import com.wontlost.web3.Chains;
import com.wontlost.web3.Web3Address;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.Web3Utils;

/**
 * Demo view exercising every feature of the web3 add-on: connecting,
 * address display, message signing, transactions, balance and chain
 * switching.
 */
@Route("")
public class DemoView extends VerticalLayout {

    private final Web3Connect wallet = new Web3Connect();
    private final Web3Address address = new Web3Address();
    private final Paragraph status = new Paragraph("Not connected.");
    private final Paragraph providerPrompt = new Paragraph(
            "No browser wallet detected. Install MetaMask: https://metamask.io/download");

    public DemoView() {
        setMaxWidth("720px");
        getStyle().set("margin", "0 auto");

        add(new H1("Vaadin Web3 Add-on Demo"),
                new Paragraph("Requires a browser wallet extension such as MetaMask. "
                        + "Use a test network (e.g. Sepolia) when trying transactions."));

        // 默认隐藏，只有浏览器明确报告没有钱包时才显示，避免已装钱包的用户每次加载都闪现提示
        providerPrompt.setVisible(false);
        wireWalletEvents();
        address.setCopyable(true);

        add(providerPrompt, wallet, address, status);
        add(signSection(), transactionSection(), chainSection());

        // Restore a previously authorized session without prompting.
        wallet.restore();
    }

    private void wireWalletEvents() {
        wallet.addProviderDetectedListener(e -> providerPrompt.setVisible(!e.isAvailable()));
        wallet.addConnectedListener(e -> {
            address.setAddress(e.getAccount());
            status.setText("Connected to chain " + e.getChainId()
                    + " as " + Web3Utils.abbreviate(e.getAccount()));
            refreshBalance();
        });
        wallet.addDisconnectedListener(e -> {
            address.setAddress(null);
            status.setText("Not connected.");
        });
        wallet.addChainChangedListener(e -> {
            status.setText("Chain changed to " + e.getChainId());
            refreshBalance();
        });
        wallet.addTransactionSentListener(e ->
                success("Transaction submitted: " + e.getHash()));
        wallet.addMessageSignedListener(e ->
                success("Signed. Signature: " + Web3Utils.abbreviate(e.getSignature())));
        wallet.addErrorListener(e -> {
            if (e.isUserRejected()) {
                Notification.show("Request rejected in wallet");
            } else {
                error("Wallet error " + e.getCode() + ": " + e.getErrorMessage());
            }
        });
    }

    private VerticalLayout signSection() {
        TextArea message = new TextArea("Message");
        message.setValue("Hello from Vaadin!");
        message.setWidthFull();
        Button sign = new Button("Sign message", e -> wallet.signMessage(message.getValue()));
        return section("Sign a message", message, sign);
    }

    private VerticalLayout transactionSection() {
        TextField to = new TextField("Recipient address");
        to.setWidthFull();
        to.setPlaceholder("0x…");
        BigDecimalField amount = new BigDecimalField("Amount (ETH)");
        amount.setValue(new BigDecimal("0.001"));
        Button send = new Button("Send transaction", e -> {
            if (!Web3Utils.isValidAddress(to.getValue())) {
                error("Enter a valid 0x… recipient address");
                return;
            }
            BigDecimal value = amount.getValue();
            if (value == null || value.signum() <= 0) {
                error("Enter an amount greater than zero");
                return;
            }
            try {
                wallet.sendTransaction(to.getValue(), Web3Utils.etherToWeiHex(value), null);
            } catch (IllegalArgumentException exception) {
                error(exception.getMessage());
            }
        });
        return section("Send a transaction", to, amount, send);
    }

    private VerticalLayout chainSection() {
        Select<String> chain = new Select<>();
        chain.setLabel("Network");
        chain.setItems("Ethereum", "Sepolia", "Polygon", "Base", "Arbitrum One");
        chain.setValue("Sepolia");
        Button switchBtn = new Button("Switch chain", e -> {
            switch (chain.getValue()) {
                case "Ethereum" -> wallet.switchChain(Chains.ETHEREUM_MAINNET);
                case "Polygon" -> wallet.switchChain(Chains.POLYGON);
                case "Base" -> wallet.switchChain(Chains.BASE, "Base", "https://mainnet.base.org", "ETH");
                case "Arbitrum One" -> wallet.switchChain(Chains.ARBITRUM_ONE,
                        "Arbitrum One", "https://arb1.arbitrum.io/rpc", "ETH");
                default -> wallet.switchChain(Chains.SEPOLIA);
            }
        });
        return section("Switch network", new HorizontalLayout(chain, switchBtn));
    }

    private void refreshBalance() {
        if (!wallet.isConnected()) {
            return;
        }
        UI ui = UI.getCurrent();
        wallet.getBalance().thenAccept(weiHex -> ui.access(() ->
                success("Balance: " + Web3Utils.weiHexToEther(weiHex)
                        .stripTrailingZeros().toPlainString() + " (native token)")));
    }

    private VerticalLayout section(String title, com.vaadin.flow.component.Component... components) {
        VerticalLayout layout = new VerticalLayout(new H2(title));
        layout.add(components);
        layout.setPadding(false);
        return layout;
    }

    private void success(String text) {
        Notification.show(text, 5000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    private void error(String text) {
        Notification.show(text, 5000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR);
    }
}
