package com.wontlost.web3.demo;

import java.math.BigDecimal;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import com.wontlost.web3.Chains;
import com.wontlost.web3.Web3Address;
import com.wontlost.web3.Web3Connect;
import com.wontlost.web3.Web3Utils;
import com.wontlost.web3.demo.ui.DemoCallout;
import com.wontlost.web3.demo.ui.DemoPage;
import com.wontlost.web3.demo.ui.DemoSection;
import com.wontlost.web3.demo.ui.DemoStatus;

/**
 * Demo view exercising every feature of the web3 add-on: connecting,
 * address display, message signing, transactions, balance and chain
 * switching.
 */
@Route(value = "", layout = MainLayout.class)
@AnonymousAllowed
public class DemoView extends DemoPage {

    private final Web3Connect wallet = new Web3Connect();
    private final Web3Address address = new Web3Address();
    private final DemoStatus status = new DemoStatus("pending", "Not connected.");
    private final Paragraph providerPrompt = new Paragraph(
            "No browser wallet detected. Install MetaMask: https://metamask.io/download");
    private final VerticalLayout walletList = new VerticalLayout();

    public DemoView() {
        super("Vaadin Web3 Add-on Demo", "Connect a browser wallet to inspect accounts, signatures, transactions, balances, and networks.");

        // 默认隐藏，只有浏览器明确报告没有钱包时才显示，避免已装钱包的用户每次加载都闪现提示
        providerPrompt.setVisible(false);
        wireWalletEvents();
        address.setCopyable(true);

        walletList.setPadding(false);
        walletList.setSpacing(true);
        add(new DemoSection("Wallet", "Connect and inspect the active account.", providerPrompt, wallet, walletList, address, status));
        add(signSection(), transactionSection(), chainSection());

        // Restore a previously authorized session without prompting.
        wallet.restore();
    }

    private void wireWalletEvents() {
        wallet.addProviderDetectedListener(e -> providerPrompt.setVisible(!e.isAvailable()));
        wallet.addWalletsChangedListener(e -> refreshWallets());
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

    private void refreshWallets() {
        walletList.removeAll();
        for (var info : wallet.getWallets()) {
            HorizontalLayout row = new HorizontalLayout();
            if (!info.icon().isBlank()) {
                Image icon = new Image(info.icon(), info.name());
                icon.addClassName("demo-wallet-icon");
                row.add(icon);
            }
            row.add(new Paragraph(info.name()), new Button("Connect with " + info.name(),
                    event -> wallet.connect(info.rdns())));
            if (wallet.isServerWallet(info)) {
                // 开发钱包：醒目提示，文案与组件 i18n 一致
                DemoCallout warning = new DemoCallout("warning", VaadinIcon.WARNING.create(),
                        wallet.getI18n().getDevelopmentWalletWarning());
                row.add(warning);
            }
            walletList.add(row);
        }
    }

    private DemoSection signSection() {
        TextArea message = new TextArea("Message");
        message.setValue("Hello from Vaadin!");
        message.setWidthFull();
        Button sign = new Button("Sign message", e -> wallet.signMessage(message.getValue()));
        return section("Sign a message", "Review and sign a message with your connected wallet.", message, sign);
    }

    private DemoSection transactionSection() {
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
        return section("Send a transaction", "Enter a recipient and amount, then submit a transaction.", to, amount, send);
    }

    private DemoSection chainSection() {
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
        return section("Switch network", "Choose a supported network and request a wallet switch.", DemoSection.row(chain, switchBtn));
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

    private DemoSection section(String title, String description, com.vaadin.flow.component.Component... components) {
        return new DemoSection(title, description, components);
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
