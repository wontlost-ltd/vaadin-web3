# Wallet login, token gates and stablecoin checkout for Vaadin

*Draft. Not yet published.*

Most Web3 tutorials assume a React frontend, a JavaScript wallet library and a separate backend. Vaadin developers can keep the application in server-side Java. The Web3 add-on connects browser wallets to Vaadin Flow components, verifies SIWE signatures on the server and checks stablecoin payments against chain receipts.

For a new Spring Boot application, start with the `web3-vaadin-spring-boot-starter` and the [step-by-step tutorial](../tutorial.md). The component and server artifacts can also be used directly by applications that do not use Spring Boot.

## Add a wallet login

The starter supplies a nonce store and applies configured SIWE expectations through `Web3SiweLoginConfigurer`:

```java
@Route("login")
@AnonymousAllowed
public class LoginView extends VerticalLayout {
    public LoginView(NonceStore nonces, Web3SiweLoginConfigurer configurer) {
        SiweLogin login = configurer.configure(new SiweLogin(nonces));
        login.addSignedInListener(event ->
                Notification.show("Welcome " + event.getSignIn().address()));
        add(login);
    }
}
```

The server checks the one-time nonce, domain, URI, chain and time window, then stores the verified identity in `Web3Session`. When an application adds Spring Security, the starter's bridge can also persist a `Web3Principal` in the HTTP session's `SecurityContext`. Applications opt into Spring Security with their own `SecurityFilterChain`; the starter does not create one automatically. See the tutorial for a protected route and logout configuration.

Smart-contract wallets are verified on chain through ERC-1271, including counterfactual wallets through ERC-6492, when the corresponding chain RPC is configured.

## Gate a view on token ownership

```java
@Route("members")
@RequiresToken(chainId = 1, token = "USDC", minBalance = "100")
public class MembersView extends VerticalLayout { }
```

The route guard uses the SIWE-verified address in `Web3Session`. A visitor without a session is sent to the configured login route with a `continue` parameter. An insufficient balance gives a 403 page; an unavailable RPC gives a 503 page.

## Accept a stablecoin payment

```java
StablecoinCheckout checkout = new StablecoinCheckout(
        chains, ledger, treasury, new BigDecimal("25.00"))
        .setOrderId(order.id());
checkout.addPaymentConfirmedListener(event ->
        orders.markPaid(order.id(), event.getResult().txHash()));
```

The component asks the selected wallet to submit a token transfer, then checks the on-chain receipt and Transfer logs, waits for configured confirmations and claims the transaction in a payment ledger. A transaction hash reported by the browser alone is not treated as payment confirmation.

## Test without a browser wallet

The `web3-vaadin-test` artifact provides `TestWallet`, `TestNonceStore`, and `SiweTestSupport`. These helpers create signed inputs for the real SIWE verifier; they do not bypass verification. `PaymentTestSupport` provides a local JSON-RPC fixture for exercising payment verification.

## Run the demo

Install Foundry, start Anvil and run the local demo profile:

```bash
anvil
mvn spring-boot:run -pl web3-vaadin-demo -Dspring-boot.run.profiles=demo
```

The Development wallet is only for a local chain. Anvil's default accounts and private keys are public and must never hold valuable assets. A plain Anvil chain with ID 31337 cannot resolve the built-in Sepolia token entries. For local checkout, run `anvil --fork-url "$SEPOLIA_RPC_URL" --chain-id 11155111`, disable the Development wallet and point `web3.chains.11155111.rpc-url` to `http://127.0.0.1:8545`. Use a wallet account holding Sepolia test tokens and configure that wallet to the local RPC. Transactions stay on the local fork, and the demo reports payment only after receipt verification.

For real-wallet testing, disable the demo profile, use a Sepolia wallet, configure `web3.demo.recipient`, and obtain test tokens from the [Circle faucet](https://faucet.circle.com/) or [Paxos faucet](https://faucet.paxos.com/).

The optional `web3-vaadin-walletconnect` module is already available for mobile wallets. It adds desktop QR and mobile deep-link flows to the same EIP-6963 wallet picker when configured with a Reown project ID.
