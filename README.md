# Web3 Add-on for Vaadin

Wallet login, token-gated views and USDC payments for Vaadin Flow, in plain
server-side Java. You don't need a JavaScript web3 library or a frontend build
step.

- **Connect any browser wallet**: MetaMask, Coinbase Wallet, Rabby, Brave and
  any other [EIP-1193](https://eips.ethereum.org/EIPS/eip-1193) provider. When
  several wallets are installed, they are discovered through
  [EIP-6963](https://eips.ethereum.org/EIPS/eip-6963) and offered in a built-in
  picker.
- **Sign-In with Ethereum** ([EIP-4361](https://eips.ethereum.org/EIPS/eip-4361)):
  the server issues a nonce and verifies the signature. Each step is checked
  on the server.
- **Token-gated routes**: add `@RequiresToken` to a view to require a token
  balance before it opens.
- **Stablecoin checkout**: accept USDC on any of the 11 built-in networks you
  register an RPC endpoint for. Each payment is checked against the on-chain
  receipt before it is confirmed.

Requires **Vaadin 25.3+** and **Java 21+**.

## What you get

| Feature | Module | Highlights |
|---|---|---|
| `Web3Connect` wallet button and API | `vaadin-web3` | Connect, restore, disconnect, sign, EIP-712, send transactions, switch or add chains |
| Multi-wallet discovery | `vaadin-web3` | EIP-6963 discovery, a keyboard-accessible picker, remembers the last wallet |
| `Web3Address` | `vaadin-web3` | Shortened address, colour badge, click to copy |
| `SiweLogin` | `vaadin-web3-server` | EIP-4361 messages, single-use nonces, signature recovery, `Web3Session` |
| `@RequiresToken` | `vaadin-web3-server` | ERC-20/ERC-721 balance gates, redirect to login with a continue link, ready-made 403/503 pages |
| `StablecoinCheckout` | `vaadin-web3-server` | USDC transfer, receipt and Transfer-log checks, confirmations, protection against reusing one transaction for two orders |
| `EthRpcClient`, `Erc20`, `Tokens` | `vaadin-web3-server` | Minimal JSON-RPC client, ERC-20 calls, built-in USDC contract addresses |

The `vaadin-web3` component module has **no third-party dependencies**. The
`vaadin-web3-server` module adds `org.web3j:crypto` for signature
verification.

## Quick start

1. Add the dependencies. Add `vaadin-web3-server` only if you need the
   server-side features.

   ```xml
   <dependency>
       <groupId>com.wontlost</groupId>
       <artifactId>vaadin-web3</artifactId>
       <version>1.0.0</version>
   </dependency>
   <dependency>
       <groupId>com.wontlost</groupId>
       <artifactId>vaadin-web3-server</artifactId>
       <version>1.0.0</version>
   </dependency>
   ```

2. Connect a wallet:

   ```java
   Web3Connect wallet = new Web3Connect();
   wallet.addConnectedListener(e -> Notification.show("Connected " + e.getAccount()));
   add(wallet);
   ```

3. Sign users in with their wallet:

   ```java
   private static final NonceStore NONCES = new InMemoryNonceStore();

   SiweLogin login = new SiweLogin(NONCES);
   login.addSignedInListener(e -> Notification.show("Signed in as " + e.getSignIn().address()));
   add(login);
   ```

4. Gate a view on a token balance, or take a payment. Both need a
   `ChainRegistry`; see [On-chain reads](#on-chain-reads).

   ```java
   @Route("holders")
   @RequiresToken(chainId = 11155111, token = "USDC", minBalance = "1")
   public class HoldersView extends VerticalLayout { }

   // chains: your ChainRegistry; ledger: e.g. new InMemoryPaymentLedger()
   add(new StablecoinCheckout(chains, ledger, "0xYourReceivingAddress", new BigDecimal("25.00")));
   ```

A full Spring Boot demo is in [`demo/`](demo). See [Running the demo](#running-the-demo).

## Wallet components (`vaadin-web3`)

### Connect a wallet

```java
Web3Connect wallet = new Web3Connect();
wallet.addConnectedListener(e ->
        Notification.show("Connected " + e.getAccount() + " on chain " + e.getChainId()));
wallet.addDisconnectedListener(e -> Notification.show("Disconnected"));
wallet.addChainChangedListener(e -> Notification.show("Chain: " + e.getChainId()));
wallet.addErrorListener(e -> {
    if (!e.isUserRejected()) {
        Notification.show("Wallet error: " + e.getErrorMessage());
    }
});
add(wallet);

// Optional: silently restore a previously authorized session on view enter
wallet.restore();
```

To hide the built-in button and drive everything from your own UI, create the
component with `new Web3Connect(true)` and call `wallet.connect()` yourself.

### Multiple wallets (EIP-6963)

`Web3Connect` discovers wallets that announce themselves through EIP-6963.
`getWallets()` returns their metadata.

- To connect a specific wallet, pass its reverse-DNS identifier to
  `connect(...)`.
- `setPreferredWallet()` sets the default wallet.
- If several wallets are found and none is selected, `connect()` opens a
  built-in, keyboard-accessible wallet picker.

```java
wallet.addWalletsChangedListener(event -> wallet.getWallets().forEach(info ->
        System.out.println(info.name() + " (" + info.rdns() + ")")));
wallet.setPreferredWallet("io.metamask");
wallet.connect("io.metamask");
```

The last wallet used successfully is remembered in browser local storage. It
is used for later connections and for silent restoration. Wallets that only
expose `window.ethereum` still work as a fallback.

### Sign messages

```java
wallet.signMessage("Hello from Example")
        .thenAccept(signature -> Notification.show("Signature: " + signature));
```

The `CompletableFuture` callbacks run while the Vaadin session is locked, so
you don't need an extra `UI.access()` call. EIP-712 typed data is supported
through `wallet.signTypedData(jsonPayload)`. To authenticate users, use
[`SiweLogin`](#sign-in-with-ethereum-siwe) instead of a raw signature.

### Send a transaction

```java
wallet.sendTransaction(
        "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
        Web3Utils.etherToWeiHex(new BigDecimal("0.05")),
        null)
    .thenAccept(hash -> getUI().get().access(() ->
            Notification.show("Submitted: " + hash)));
```

To pass other transaction fields (`gas`, `data`, ...), use
`sendTransaction(Map<String, String>)`.

### Switch or add a chain

```java
wallet.switchChain(Chains.POLYGON);

// Adds the chain to the wallet first if it is unknown:
wallet.switchChain(Chains.BASE, "Base", "https://mainnet.base.org", "ETH");
```

### Show an address

```java
Web3Address address = new Web3Address(wallet.getAccount(), true); // copyable
add(address);
```

### Events

`Web3Connect` fires these events:

- `Web3ConnectedEvent`
- `Web3DisconnectedEvent`
- `ChainChangedEvent`
- `TransactionSentEvent`
- `MessageSignedEvent`
- `Web3ErrorEvent`, which carries the EIP-1193 error code. `isUserRejected()`
  is true for code 4001.

Other checks:

- `addProviderDetectedListener()` observes `ProviderDetectedEvent`.
- `isProviderAvailable()` tells you whether a wallet provider is available.
- Failed futures complete with `Web3Connect.Web3Exception`. Use `getCode()` or
  `isUserRejected()` to see what went wrong.

`Web3Address` fires `AddressCopiedEvent`.

### Disconnecting

After a disconnect, the add-on does not reconnect silently, either through
`restore()` or when the wallet changes accounts. It also calls
`wallet_revokePermissions` when the wallet supports it. The disconnected state
applies to the whole site. It is shared with other instances on the same page
and with other tabs.

## Server features (`vaadin-web3-server`)

### Sign-In with Ethereum (SIWE)

`SiweLogin` handles the whole sign-in on the server:

1. It issues a one-time nonce.
2. It asks the connected wallet to sign an
   [EIP-4361](https://eips.ethereum.org/EIPS/eip-4361) message.
3. It verifies the personal signature.
4. It stores the verified identity in the current Vaadin session.

Keep a single `InMemoryNonceStore` for the whole application:

```java
private static final NonceStore NONCES = new InMemoryNonceStore();

SiweLogin login = new SiweLogin(NONCES)
        .setDomain("example.com")
        .setUri("https://example.com")
        .setStatement("Sign in to Example");
login.addSignedInListener(event -> {
    VerifiedSignIn user = event.getSignIn();
    Notification.show("Signed in as " + user.address());
});
login.addSignInFailedListener(event ->
        Notification.show("Sign-in failed: " + event.getReason()));
add(login);

Web3Session.current().ifPresent(user ->
        Notification.show("Current account: " + user.address()));
login.signOut(); // Clears the verified session and disconnects the wallet by default
```

If you don't set the domain and URI, the component derives them from the
request. It checks these sources in order:

1. The first `Forwarded` value.
2. `X-Forwarded-Host` / `X-Forwarded-Proto`.
3. The request `Host` header and secure flag.

In production, set `setDomain()` and `setUri()` explicitly, especially behind
a reverse proxy, so that the SIWE origin matches the public page origin.

If the component can be deserialized outside the request that created it,
register the application nonce store with
`SiweLogin.registerNonceStore(VaadinContext, NonceStore)`.

After sign-in, users go back to the page they first requested, taken from
the `continue` query parameter. Only relative paths on the same site are
followed. Smart-contract wallet signatures (EIP-1271) are not supported yet.

### On-chain reads

The server module provides an `EthRpcClient` that works over either a JSON-RPC
URL or a custom `JsonRpcTransport`.

- `Erc20` builds common ERC-20 calls and reads `balanceOf` / `decimals`.
- `Tokens.usdc(chainId)` returns the built-in USDC contracts.
- `Tokens.toBaseUnits()` converts display amounts without rounding.

Register your RPC clients in an application-scoped `ChainRegistry` and store
it in the `VaadinContext`. Token gates and payment verification read it from
there:

```java
@Bean
VaadinServiceInitListener web3Chains() {
    ChainRegistry chains = new ChainRegistry();
    chains.register(11155111, "https://ethereum-sepolia-rpc.publicnode.com"); // Sepolia
    return event -> event.getSource().getContext().setAttribute(ChainRegistry.class, chains);
}
```

Built-in USDC addresses come from Circle's documentation:

| Network | Chain id |
|---|---|
| Ethereum | 1 |
| Sepolia | 11155111 |
| Base | 8453 |
| Base Sepolia | 84532 |
| Arbitrum One | 42161 |
| Arbitrum Sepolia | 421614 |
| OP Mainnet | 10 |
| OP Sepolia | 11155420 |
| Polygon | 137 |
| Polygon Amoy | 80002 |
| Avalanche C-Chain | 43114 |

### Token-gated views

Add `@RequiresToken` to a route to require a wallet balance. A route listener
is registered automatically, including under Spring Boot. It checks the
**SIWE-verified** address stored in `Web3Session`. The address the browser
reports is never used.

```java
@Route("holders")
@RequiresToken(chainId = 11155111, token = "USDC", minBalance = "1")
public class HoldersView extends VerticalLayout { }

// Any ERC-20 or ERC-721 contract; decimals = 0 means "owns at least one NFT"
@RequiresToken(chainId = 1, token = "0xYourNftContract", minBalance = "1", decimals = 0)
```

The listener handles each case as follows:

| Situation | Result |
|---|---|
| Visitor isn't signed in | Forwarded to `redirectTo` (default `login`) with `?continue=<path>`. `SiweLogin` returns them to the view after sign-in. |
| Balance is below the minimum | 403 page, shown by `TokenGateDeniedView` |
| RPC failure | 503 page, shown by `TokenGateUnavailableView` |
| No `ChainRegistry` registered | 503 page (the gate fails closed). A warning is logged. |

To use your own 403 and 503 pages, implement
`HasErrorParameter<TokenGateDeniedException>` or
`HasErrorParameter<TokenGateUnavailableException>` in your app. Your views take
priority over the built-in ones.

Balances are cached per browser tab for 30 seconds. A holder who moves their
tokens away therefore keeps access for at most that long.

### Stablecoin checkout

`StablecoinCheckout` asks the connected wallet to transfer USDC. It then
checks the transaction receipt and Transfer logs on chain before it reports
success.

```java
StablecoinCheckout checkout = new StablecoinCheckout(chains, ledger, recipient, new BigDecimal("25.00"))
        .setOrderId(order.id())
        .setPreferredChain(8453); // Base
checkout.addPaymentConfirmedListener(e -> orders.markPaid(order.id(), e.getResult().txHash()));
checkout.addPaymentFailedListener(e -> {
    if (!e.isUserRejected()) Notification.show("Payment failed: " + e.getResult().status());
});
add(checkout);
```

How it works:

- **Accepted payment**: a payment is confirmed only when all of these hold:
  - The receipt succeeded.
  - The USDC Transfer logs sent to `recipient` add up to at least the amount.
  - The transaction has reached the required number of confirmations
    (`setMinConfirmations`).
- **Payer check**: when the user signed in with SIWE, the payer must also
  match the verified address. Otherwise the checkout uses the account the
  wallet reported.
- **One transaction, one order**: the `PaymentLedger` makes sure a
  transaction is claimed by only one order. `InMemoryPaymentLedger` is local
  to one process. Implement `PaymentLedger` on top of your database when the
  app runs on more than one node.
- **Background verification**: verification runs on a bounded background
  executor. The UI is updated through `UI.access()`, and Vaadin polling
  delivers the update, so `@Push` isn't required.
- **After confirmation**: the button shows "Paid" and stays disabled. Call
  `reset(newOrderId)` to start another order. Results that arrive after a
  reset are discarded.
- **Possible results**: `CONFIRMED`, `PENDING`, `CONFIRMING`, `FAILED`,
  `UNDERPAID`, `NO_MATCHING_TRANSFER` and `ALREADY_CLAIMED`.

If you verify payments yourself with `PaymentVerifier`, always build the
`PaymentRequest` on the server from your own order data. Never build it from
values sent by the browser.

## Security model

- **Connected addresses come from the client.** Use `Web3Session`, which is
  filled only after server-side SIWE verification, for anything that needs
  authorization.
- **Nonces are single-use and expire after 5 minutes by default.** A SIWE
  message is accepted only if all of these hold:
  - Its domain, URI and chain match.
  - Its time window is valid.
  - The recovered signer equals the address in the message.
- **Payments are checked against the chain,** not against the transaction hash
  the wallet returns.
- **The add-on never handles private keys.** The user confirms every signature
  and transaction in their own wallet.

## Running the demo

```bash
mvn install -DskipTests
mvn spring-boot:run -pl demo    # http://localhost:8080
```

| Route | Shows |
|---|---|
| `/` | Wallet connection, EIP-6963 wallets, signing, transactions, chain switching |
| `/login` | Sign-In with Ethereum |
| `/holders` | A view gated on 1 Sepolia USDC |
| `/checkout` | A 1.00 Sepolia USDC checkout |

You need a browser wallet extension such as MetaMask on the Sepolia test
network. You can get test USDC from the
[Circle faucet](https://faucet.circle.com/). Before trying the checkout, set
`web3.demo.recipient` in `demo/src/main/resources/application.properties` to
an address you control.

## Project structure

- `addon/`: wallet components (`com.wontlost:vaadin-web3`), with no
  third-party dependencies.
- `server/`: SIWE, on-chain reads, token gates and checkout
  (`com.wontlost:vaadin-web3-server`).
- `demo/`: a Spring Boot demo application that exercises every feature.

## Development

```bash
mvn verify                               # build everything and run the unit tests
cd addon && npm ci && npm test           # frontend unit tests (Vitest)
mvn install -Pdirectory -pl addon -am    # also builds the Vaadin Directory zip
```

## Roadmap

- EIP-1271 smart-contract wallet signatures (Safe, Coinbase Smart Wallet)
- WalletConnect for mobile wallets
- More stablecoins (USDT, EURC)

See [CHANGELOG.md](CHANGELOG.md) for release notes.

## License

Apache License 2.0
