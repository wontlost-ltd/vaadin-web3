# Web3 Add-on for Vaadin

Wallet login, token-gated views and stablecoin payments for Vaadin Flow, in plain
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
- **Stablecoin checkout**: accept USDC, USDT, EURC or PYUSD on supported
  networks where you register an RPC endpoint. Each payment is checked against
  the on-chain receipt before it is confirmed.

Requires **Vaadin 25.3+** and **Java 21+**.

## What you get

| Feature | Module | Highlights |
|---|---|---|
| `Web3Connect` wallet button and API | `vaadin-web3` | Connect, restore, disconnect, sign, EIP-712, send transactions, switch or add chains |
| Multi-wallet discovery | `vaadin-web3` | EIP-6963 discovery, a keyboard-accessible picker, remembers the last wallet |
| `Web3Address` | `vaadin-web3` | Shortened address, colour badge, click to copy |
| WalletConnect mobile wallets | `vaadin-web3-walletconnect` | EIP-6963 discovery, desktop QR codes, mobile wallet deep links, lazy-loaded provider |
| `SiweLogin` | `vaadin-web3-server` | EIP-4361 messages, single-use nonces, ordinary and smart-contract wallets (ERC-1271, ERC-6492), `Web3Session` |
| `@RequiresToken` | `vaadin-web3-server` | ERC-20/ERC-721 balance gates, redirect to login with a continue link, ready-made 403/503 pages |
| `StablecoinCheckout` | `vaadin-web3-server` | USDC, USDT, EURC or PYUSD transfer, receipt and Transfer-log checks, confirmations, protection against reusing one transaction for two orders; optional hosted monitoring |
| `PaymentMonitorClient`, `WebhookSignatures` | `vaadin-web3-server` | Connect a checkout to the hosted monitor and verify timestamped HMAC webhooks |
| Hosted payment monitor service | `vaadin-web3-monitor` | Continuously verifies intents using configured RPC endpoints and retries signed merchant webhooks; executable Spring Boot service |
| `FiatOnrampButton` | `vaadin-web3-onramp` | Hosted card purchases through MoonPay, Transak or Coinbase with registered contract matching and popup fallback |
| `EthRpcClient`, `Erc20`, `Tokens` | `vaadin-web3-server` | Minimal JSON-RPC client, ERC-20 calls, built-in stablecoin contract addresses |

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

### Mobile wallets (WalletConnect)

Add the optional dependency and a Reown project ID from
[cloud.reown.com](https://cloud.reown.com):

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>vaadin-web3-walletconnect</artifactId>
    <version>1.0.0</version>
</dependency>
```

Add one component to a shared layout so all wallet connectors on the page can
discover it, including those inside `SiweLogin` and `StablecoinCheckout`:

```java
add(new WalletConnect(projectId)
        .setChains(11155111, 84532));
```

WalletConnect then appears in the existing wallet picker. If it's the only
wallet available, `connect()` uses it directly.

- **Desktop:** browsers show a QR code to scan with a phone wallet.
- **Mobile:** browsers show a list of wallet apps that open through deep links.
- **Supported chains:** only the chains passed to `setChains()` are available.
  The first one is the default. `switchChain()` to any other chain fails.
- **Closing the QR modal** is reported like any other cancellation:
  `isUserRejected()` is `true` (code 4001).
- **Loading:** the WalletConnect package and its modal load only when a user
  picks WalletConnect. Pages where nobody uses it pay nothing.
- **Sessions:** a user's session is restored by `wallet.restore()` after a
  reload. On page load the library is loaded only when a stored session is
  found, or when the browser can't report whether one exists.
- **App metadata:** `setMetadata(name, description, url, iconUrl)` sets what
  the wallet shows about your app. It defaults to the page title and origin.
  Add your domain to the Reown project so that wallets can verify it.
- **RPC endpoints:** `setRpcUrl(chainId, url)` overrides the RPC endpoint that
  WalletConnect uses for read calls.

The demo enables WalletConnect when `WEB3_WALLETCONNECT_PROJECT_ID` is set:

```bash
WEB3_WALLETCONNECT_PROJECT_ID=<your project id> mvn spring-boot:run -pl demo
```

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
followed.

#### Smart-contract wallets (ERC-1271 and ERC-6492)

Smart-contract wallets such as Safe, Coinbase Smart Wallet and other ERC-4337
accounts don't sign with a single private key. `SiweLogin` accepts them
automatically once a [`ChainRegistry`](#on-chain-reads) with an RPC endpoint
for the signing chain is stored in the `VaadinContext`.

When a signature doesn't recover to the address in the message, it is checked
on chain:

- **[ERC-1271](https://eips.ethereum.org/EIPS/eip-1271)**: deployed wallets
  are asked whether the signature is valid (`isValidSignature`).
- **[ERC-6492](https://eips.ethereum.org/EIPS/eip-6492)**: wallets that
  haven't been deployed yet are also accepted. A new Coinbase Smart Wallet is
  in this state until its first transaction.

All of this happens in a single read-only `eth_call`, and nothing is deployed
or written to the chain. Signatures from ordinary wallets are still verified
locally, without any RPC call.

| Situation | Result |
|---|---|
| The wallet accepts the signature | Signed in as the wallet address |
| The wallet rejects it, or the call reverts | `ADDRESS_MISMATCH` or `SIGNATURE_INVALID` |
| The RPC endpoint can't be reached | `SIGNATURE_UNVERIFIABLE`, so the user can retry |
| No RPC client for the message's chain | Only ordinary wallet signatures are accepted |

Before the on-chain check, the verifier asks the nonce store whether the nonce
is still live, so requests with made-up nonces never reach your RPC endpoint.
`InMemoryNonceStore` does this check. A custom `NonceStore` gets the same
protection only if it implements `isActive()`.

The on-chain check is one blocking call, limited by the transport timeout
(10 seconds by default). Outside `SiweLogin`, pass the registry to
`new SiweVerifier(nonces, clock, chains)`. To check signatures over other
hashes, use `SignatureValidator.isValidSignature(client, address, hash,
signature)`.

### On-chain reads

The server module provides an `EthRpcClient` that works over either a JSON-RPC
URL or a custom `JsonRpcTransport`.

- `Erc20` builds common ERC-20 calls and reads `balanceOf` / `decimals`.
- `Tokens.find(symbol, chainId)` looks up built-in USDC, USDT, EURC and PYUSD contracts. `Tokens.usdc(chainId)` remains available for USDC.
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

### Supported stablecoins

Every registered token has 6 decimal places. Addresses in the registry are
from issuer documentation: [Circle](https://developers.circle.com/stablecoins/eurc-contract-addresses),
[Tether](https://tether.to/en/supported-protocols/) / [USDT0](https://docs.usdt0.to/) and
[Paxos](https://docs.paxos.com/guides/stablecoin/pyusd/mainnet).

| Token | Networks (chain id) | Notes |
|---|---|---|
| USDC | Ethereum (1), Sepolia (11155111), Base (8453), Base Sepolia (84532), Arbitrum One (42161), Arbitrum Sepolia (421614), OP Mainnet (10), OP Sepolia (11155420), Polygon (137), Polygon Amoy (80002), Avalanche C-Chain (43114) | Circle |
| USDT | Ethereum (1), Avalanche C-Chain (43114), Arbitrum One (42161), OP Mainnet (10), Polygon (137) | USDT0 on Arbitrum, OP Mainnet and Polygon |
| EURC | Ethereum (1), Base (8453), Avalanche C-Chain (43114), Sepolia (11155111), Base Sepolia (84532), Avalanche Fuji (43113) | Circle |
| PYUSD | Ethereum (1), Arbitrum One (42161), Polygon (137), Sepolia (11155111), Arbitrum Sepolia (421614), Polygon Amoy (80002) | Paxos |

### Token-gated views

Add `@RequiresToken` to a route to require a wallet balance. A route listener
is registered automatically, including under Spring Boot. It checks the
**SIWE-verified** address stored in `Web3Session`. The address the browser
reports is never used.

```java
@Route("holders")
@RequiresToken(chainId = 11155111, token = "USDC", minBalance = "1")
public class HoldersView extends VerticalLayout { }

// Built-in symbols are case-insensitive; contract addresses can also be used.
@RequiresToken(chainId = 42161, token = "usdt", minBalance = "10")
public class UsdtHoldersView extends VerticalLayout { }

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

### Fiat on-ramp (buy with card)

The optional `vaadin-web3-onramp` module opens hosted card-purchase sessions with
MoonPay, Transak or Coinbase. Add it alongside `vaadin-web3-server`:

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>vaadin-web3-onramp</artifactId>
    <version>1.0.0</version>
</dependency>
```

Create a provider on the server from credentials issued by the provider's
merchant dashboard, then connect it to a checkout:

```java
checkout.setOnrampAction(FiatOnrampButton.forCheckout(provider));
```

MoonPay requires a publishable key and secret key. `pk_test_` keys use the
MoonPay sandbox; `pk_live_` keys use production. Transak requires an API key,
API secret and referrer domain; its staging environment is available with the
staging flag. Coinbase uses a CDP API key ID and private key and has no sandbox.
Keep all credentials on the server, and register the provider once at startup:

```java
@Bean
VaadinServiceInitListener onrampRegistration(OnrampProvider provider) {
    return event -> OnrampProviders.register(event.getSource().getContext(), provider);
}
```

Credentials are never written into the Vaadin session. Components keep only the
provider name and look the provider up in this registry again after a session is
deserialized, for example after a restart with persistent sessions. Registering
also starts loading the provider's currency list in the background.

- **Page load:** building the checkout never waits for the provider. Until the
  currency list has loaded, or while the provider is unreachable, the
  "Buy with card" button is simply not shown. An expired list is refreshed in
  the background while the previous one stays in use.
- **Click:** creating the purchase session happens in the click callback, with a
  ten-second request timeout. If the browser blocks the new window, a link the
  user can follow is shown instead.
- **Client IP:** the user's IP sent to the provider (Transak requires it) comes
  from the request's remote address. Behind a reverse proxy, configure your
  container to trust the proxy's forwarding headers (in Spring Boot,
  `server.forward-headers-strategy=native`).

Production currency support is discovered from the provider's live catalog and
matched by chain ID and case-insensitive contract address against this library's
`Tokens` registry. A currency with a different contract is rejected even when
its display name matches. For example, MoonPay's `usdt_optimism` catalog entry
uses the legacy bridged USDT contract (`0x94b0…8e58`), while this library
registers USDT0 (`0x01bF…1071`) on Optimism; the entry is therefore not offered.

Sandbox and staging catalogs match by symbol and chain ID because those
providers issue their own test tokens. Test purchases may deliver provider test
USDC rather than Circle USDC. `StablecoinCheckout` does not recognize those
provider test tokens as payment.

Partner fees are paid into the integrating merchant's own provider account.
Transak lets merchants configure a partner fee in its dashboard. This library
does not collect partner fees or pay revenue to its authors.

### Stablecoin checkout

`StablecoinCheckout` asks the connected wallet to transfer a built-in
stablecoin. By default it accepts USDC; use `setTokens("USDC", "PYUSD")` to
show a token selector. Accepted tokens must share a currency because the
checkout has one amount. It checks the transaction receipt and Transfer logs
on chain before it reports success.

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
  - The selected token's Transfer logs sent to `recipient` add up to at least the amount.
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
- **Only new transactions count**: a transaction mined more than two minutes
  before the user clicked Pay is rejected as `PREDATES_ORDER`. The two minutes
  absorb clock differences and can be changed with
  `setTransactionTimeTolerance(...)`. This stops older transfers to your address
  from being passed off as payment for a new order: someone pretending to be an
  earlier buyer by reporting that buyer's address and transaction hash, or a
  buyer reusing their own earlier payment after an in-memory ledger was reset.
- **What it can't prove**: the transaction hash comes from the browser, so
  nothing proves which order a transfer was meant for. Without SIWE, an
  unrelated transfer to your address mined inside the tolerance window could
  still be claimed. For checkouts where that matters, require users to sign in
  with SIWE so that the payer is a verified address.
- **Possible results**: `CONFIRMED`, `PENDING`, `CONFIRMING`, `FAILED`,
  `UNDERPAID`, `NO_MATCHING_TRANSFER`, `ALREADY_CLAIMED` and `PREDATES_ORDER`.

If you verify payments yourself with `PaymentVerifier`, always build the
`PaymentRequest` on the server from your own order data. Never build it from
values sent by the browser. Set its `notBefore` to the time the order was
created.

Payment events expose `getToken()` so applications can identify the token
contract and symbol used. It can be `null` when a failure occurs before the
token is determined.

#### Hosted payment monitor

Use hosted monitoring when payment confirmation must continue after the buyer closes the checkout page, or when merchants should not operate their own chain RPC polling. The separate `vaadin-web3-monitor` service watches the chain through RPC, stores payment state in JDBC, and retries signed webhooks. It never holds funds or private keys.

```text
Vaadin checkout -> PaymentMonitorClient -> monitor API -> JDBC intent/outbox
                                                    -> configured chain RPC
merchant webhook <- signed delivery worker <- terminal payment event
```

Register `PaymentMonitorClient` once in the application's `VaadinContext`, then enable `.setPaymentMonitor(true)` on `StablecoinCheckout`. The component keeps only the payment ID. See the [hosted monitor guide](docs/MONITOR.md) for deployment, API examples, webhook verification and configuration.

## Security model

- **Connected addresses come from the client.** Use `Web3Session`, which is
  filled only after server-side SIWE verification, for anything that needs
  authorization.
- **Nonces are single-use and expire after 5 minutes by default.** A SIWE
  message is accepted only if all of these hold:
  - Its domain, URI and chain match.
  - Its time window is valid.
  - The recovered signer equals the address in the message, or the
    smart-contract wallet at that address confirms the signature on chain.
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
| `/checkout` | A 1.00 Sepolia USDC or PYUSD checkout |
| `/payments` | Recorded payments, status/date filters and CSV download |

You need a browser wallet extension such as MetaMask on the Sepolia test
network. You can get test USDC from the
[Circle faucet](https://faucet.circle.com/), and test PYUSD from the
[Paxos faucet](https://faucet.paxos.com/). Before trying the checkout, set
`web3.demo.recipient` in `demo/src/main/resources/application.properties` to
an address you control.

## Project structure

- `addon/`: wallet components (`com.wontlost:vaadin-web3`), with no
  third-party dependencies.
- `walletconnect/`: optional WalletConnect v2 mobile wallet integration
  (`com.wontlost:vaadin-web3-walletconnect`).
- `server/`: SIWE, on-chain reads, token gates and checkout (`com.wontlost:vaadin-web3-server`).
- `onramp/`: hosted fiat-to-stablecoin purchases (`com.wontlost:vaadin-web3-onramp`).
- `pro/`: optional commercial persistence, screening and payment operations (`com.wontlost:vaadin-web3-pro`; license DRAFT).
- `monitor/`: optional hosted payment tracking service (`com.wontlost:vaadin-web3-monitor`).
- `demo/`: a Spring Boot demo application that exercises every feature.

## Pro (commercial)

The optional `vaadin-web3-pro` module adds JDBC-backed SIWE nonce storage and
payment claims for clustered applications, Chainalysis sanctions screening
with an audit log and local deny-list composition, and payment records with a
filtered Vaadin view and CSV export. The Apache 2.0 modules retain all existing
features. `AddressScreening` is an open-source extension point that applications
may implement themselves.

Pro is not included in the Maven Central release list. Its license is marked
**DRAFT and has not been issued**; no commercial terms are available yet. For
inquiries, contact [service@wontlost.com](mailto:service@wontlost.com).
The demo enables audited screening only when `web3.rpc.1` is configured with an
Ethereum mainnet RPC URL, for example `https://ethereum-rpc.publicnode.com`.

## Development

```bash
mvn verify                               # build everything and run the unit tests
(cd addon && npm ci && npm test)         # frontend unit tests (Vitest)
(cd walletconnect && npm ci && npm test) # WalletConnect frontend unit tests
mvn install -Pdirectory -pl addon -am    # also builds the Vaadin Directory zip
```

See [CHANGELOG.md](CHANGELOG.md) for release notes.

## License

Apache License 2.0
