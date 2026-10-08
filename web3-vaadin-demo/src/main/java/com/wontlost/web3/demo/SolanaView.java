package com.wontlost.web3.demo;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.wontlost.web3.identity.Web3Identity;
import com.wontlost.web3.siwe.Web3Session;
import com.wontlost.web3.siws.SiwsVerifier;
import com.wontlost.web3.siws.SolanaCluster;
import com.wontlost.web3.solana.SolanaBalance;
import com.wontlost.web3.solana.SolanaRpcClient;
import com.wontlost.web3.solana.SolanaRpcException;
import com.wontlost.web3.solana.SplTokenBalance;
import com.wontlost.web3.solana.LatestBlockhash;
import com.wontlost.web3.solana.SolanaPrograms;
import com.wontlost.web3.solana.SolanaTransaction;
import com.wontlost.web3.solana.SolanaTransfers;
import com.wontlost.web3.solana.wallet.SiwsLogin;
import com.wontlost.web3.solana.wallet.SolanaTransactionStatus;

/** Sign-In With Solana 演示页：登录、查看身份，以及按登录地址读取 SOL 与 SPL 余额。 */
@Route(value = "solana", layout = MainLayout.class)
@PageTitle("Solana")
@AnonymousAllowed
public final class SolanaView extends VerticalLayout {
    private static final BigInteger AIRDROP_LAMPORTS = BigInteger.valueOf(1_000_000_000L);

    private final SolanaRpcClient rpc;
    private final SolanaCluster cluster;
    private final Span identity = new Span();
    private final Span status = new Span();
    private final Span solBalance = new Span();
    private final Span tokenBalance = new Span();
    private final TextField mint = new TextField("SPL token mint");
    private final Button signOut = new Button("Sign out");
    private final Button refresh = new Button("Refresh SOL balance");
    private final Button checkToken = new Button("Check token balance");
    private final Button airdrop = new Button("Request 1 SOL airdrop");
    private final TextField recipient = new TextField("Recipient wallet");
    private final TextField amount = new TextField("Amount");
    private final Button send = new Button("Send");
    private final Span transferHint = new Span("Sends SOL, or the SPL token above when a mint is entered.");
    private final SolanaTransactionStatus transferStatus;
    private final SiwsLogin login;

    public SolanaView(SiwsVerifier verifier, SolanaCluster cluster, SolanaRpcClient rpc) {
        this.rpc = rpc;
        this.cluster = cluster;
        login = new SiwsLogin(verifier, cluster).setStatement("Sign in to the Vaadin Web3 demo");
        transferStatus = new SolanaTransactionStatus(rpc, cluster);
        transferStatus.setId("solana-transfer-status");
        transferStatus.addStatusChangedListener(event -> {
            if (event.getStatus() == SolanaTransactionStatus.Status.CONFIRMED
                    || event.getStatus() == SolanaTransactionStatus.Status.FINALIZED) {
                showSolBalance();
                if (!mint.isEmpty()) showTokenBalance();
            }
        });
        recipient.setWidth("32rem");
        amount.setWidth("10rem");
        send.addClickListener(event -> sendTransfer());
        status.getElement().setAttribute("role", "status");
        status.setId("solana-status");
        identity.setId("solana-identity");
        solBalance.setId("solana-sol-balance");
        tokenBalance.setId("solana-token-balance");
        mint.setWidth("32rem");
        mint.setHelperText("Base58 mint address, for example a token created with spl-token on the local validator");
        airdrop.setVisible(cluster == SolanaCluster.LOCALNET || cluster == SolanaCluster.DEVNET);

        login.addSignedInListener(event -> {
            status.setText("Signed in.");
            update();
        });
        login.addSignInFailedListener(event -> status.setText(event.getLocalizedMessage()));
        signOut.addClickListener(event -> {
            login.signOut();
            status.setText("Signed out.");
            update();
        });
        refresh.addClickListener(event -> showSolBalance());
        checkToken.addClickListener(event -> showTokenBalance());
        airdrop.addClickListener(event -> requestAirdrop());

        add(new H2("Sign in with Solana"),
                new Paragraph("Cluster: " + cluster.chainId() + ". Connect a Wallet Standard wallet such as Phantom, "
                        + "or the development wallet when it is enabled, and sign the challenge."),
                login, status, identity,
                new HorizontalLayout(signOut),
                new H3("Balances"),
                new HorizontalLayout(solBalance, refresh, airdrop),
                new HorizontalLayout(mint, checkToken),
                tokenBalance,
                new H3("Send"),
                transferHint,
                new HorizontalLayout(recipient, amount, send),
                transferStatus);
        update();
    }

    private void update() {
        String address = address();
        identity.setText(address == null ? "Not signed in." : "Signed in as " + address + " ("
                + Web3Session.currentIdentity().orElseThrow().account().caip10() + ")");
        signOut.setEnabled(address != null);
        refresh.setEnabled(address != null);
        checkToken.setEnabled(address != null);
        airdrop.setEnabled(address != null);
        send.setEnabled(address != null);
        solBalance.setText("");
        tokenBalance.setText("");
        if (address != null) showSolBalance();
    }

    private void showSolBalance() {
        String address = address();
        if (address == null) return;
        try {
            SolanaBalance balance = rpc.getBalance(address);
            solBalance.setText(balance.sol().stripTrailingZeros().toPlainString() + " SOL (slot " + balance.slot() + ")");
        } catch (SolanaRpcException | IllegalArgumentException exception) {
            solBalance.setText("SOL balance unavailable: " + exception.getMessage());
        }
    }

    private void showTokenBalance() {
        String address = address();
        if (address == null) return;
        try {
            SplTokenBalance balance = rpc.getTokenBalance(address, mint.getValue().trim());
            tokenBalance.setText(balance.uiAmount().stripTrailingZeros().toPlainString() + " (" + balance.amount()
                    + " base units, " + balance.decimals() + " decimals, slot " + balance.slot() + ")");
        } catch (SolanaRpcException | IllegalArgumentException exception) {
            tokenBalance.setText("Token balance unavailable: " + exception.getMessage());
        }
    }

    private void requestAirdrop() {
        String address = address();
        if (address == null) return;
        try {
            rpc.request("requestAirdrop", List.of(address, AIRDROP_LAMPORTS, Map.of("commitment", "confirmed")));
            status.setText("Airdrop requested; refresh the balance in a few seconds.");
        } catch (SolanaRpcException exception) {
            status.setText("Airdrop failed: " + exception.getMessage());
        }
    }

    /** 服务端构建未签名交易，由已连接的钱包签名并发送；状态组件随后跟踪确认进度。 */
    private void sendTransfer() {
        String sender = address();
        if (sender == null) return;
        if (!sender.equals(login.getWallet().getAccount())) {
            status.setText("Connect the wallet you signed in with before sending.");
            return;
        }
        LatestBlockhash latest;
        SolanaTransaction transaction;
        try {
            BigDecimal value = new BigDecimal(amount.getValue().trim());
            String to = recipient.getValue().trim();
            latest = rpc.getLatestBlockhash();
            transaction = mint.isEmpty() ? SolanaTransfers.sol(sender, to,
                    value.movePointRight(SolanaBalance.DECIMALS).toBigIntegerExact(), latest.blockhash())
                    : tokenTransfer(sender, to, value, latest.blockhash());
        } catch (ArithmeticException exception) {
            status.setText("The amount has more decimals than the asset supports.");
            return;
        } catch (IllegalArgumentException | IllegalStateException | SolanaRpcException exception) {
            status.setText("Cannot build the transfer: " + exception.getMessage());
            return;
        }
        send.setEnabled(false);
        status.setText("Approve the transaction in your wallet.");
        long lastValid = latest.lastValidBlockHeight();
        login.getWallet().signAndSendTransaction(transaction.unsignedWire(), rpc).whenComplete((signature, error) -> {
            send.setEnabled(true);
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                status.setText("The transfer was not sent: " + cause.getMessage());
                return;
            }
            status.setText("Sent " + signature);
            transferStatus.track(signature, lastValid);
        });
    }

    private SolanaTransaction tokenTransfer(String sender, String to, BigDecimal value, String blockhash) {
        String tokenMint = mint.getValue().trim();
        SolanaTransfers.requireWalletRecipient(rpc, to);
        String program = rpc.getAccountOwner(tokenMint).filter(SolanaPrograms::isTokenProgram)
                .orElseThrow(() -> new IllegalStateException("the mint is not an SPL token"));
        int decimals = rpc.getTokenBalance(sender, tokenMint).decimals();
        return SolanaTransfers.spl(sender, tokenMint, to, value.movePointRight(decimals).toBigIntegerExact(), decimals,
                program, blockhash, true);
    }

    private String address() {
        return Web3Session.currentIdentity().map(Web3Identity::account)
                .filter(account -> "solana".equals(account.namespace())).map(account -> account.address()).orElse(null);
    }
}
