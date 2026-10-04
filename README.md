# Web3 Add-on for Vaadin

Vaadin components for web3 support: connect browser wallets (MetaMask,
Coinbase Wallet, Brave, Rabby — any [EIP-1193](https://eips.ethereum.org/EIPS/eip-1193)
provider), sign messages, send transactions, switch chains, and display
addresses — all from a server-side Java API. No bundled web3 JavaScript
library; the components talk to the injected provider directly.

Requires **Vaadin 25.3+** and **Java 21+**.

## Components

| Component | Tag | Purpose |
|---|---|---|
| `Web3Connect` | `<web3-connect>` | EIP-6963 wallet discovery, connect/disconnect button + full wallet API |
| `Web3Address` | `<web3-address>` | Abbreviated address display with color badge and click-to-copy |

Plus server-side helpers: `Chains` (common chain ids, hex/decimal conversion)
and `Web3Utils` (address validation, abbreviation, wei/ether conversion).

## Installation

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>vaadin-web3</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Usage

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

The built-in button can be hidden (`new Web3Connect(true)`) to drive
everything from your own UI via `wallet.connect()`.

### Multiple wallets (EIP-6963)

`Web3Connect` discovers wallets that announce through EIP-6963 and exposes
their metadata with `getWallets()`. Connect to a specific wallet using its
reverse-DNS identifier; `setPreferredWallet()` selects the default. Calling
`connect()` with multiple discovered wallets and no selection opens a built-in
keyboard accessible wallet picker.

```java
wallet.addWalletsChangedListener(event -> wallet.getWallets().forEach(info ->
        System.out.println(info.name() + " (" + info.rdns() + ")")));
wallet.setPreferredWallet("io.metamask");
wallet.connect("io.metamask");
```

The last successfully used wallet is remembered in browser local storage for
subsequent connections and silent restoration. Existing `window.ethereum`
wallets remain supported as a fallback.

### Sign a message (e.g. Sign-In with Ethereum)

```java
wallet.signMessage("Log in to Example at " + Instant.now())
        .thenAccept(signature -> Notification.show("Signature: " + signature));
```

The `CompletableFuture` callbacks run while the Vaadin session is locked, so no extra `UI.access()` call is needed.

EIP-712 typed data is supported through `wallet.signTypedData(jsonPayload)`.

### Sign-In with Ethereum (SIWE)

Add the server module alongside the Vaadin web3 add-on:

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>vaadin-web3-server</artifactId>
    <version>1.0.0</version>
</dependency>
```

`SiweLogin` issues a one-time server nonce, asks the connected wallet to sign an
[EIP-4361](https://eips.ethereum.org/EIPS/eip-4361) message, verifies the
personal signature and stores the verified identity in the current Vaadin
session. Keep a single `InMemoryNonceStore` for the application:

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

When domain and URI are omitted, the component derives them from the first
`Forwarded` value, then `X-Forwarded-Host` / `X-Forwarded-Proto`, and finally
the request `Host` header and secure flag. Configure `setDomain()` and
`setUri()` explicitly in production, especially behind a reverse proxy, so
the SIWE origin matches the public page origin. Register the application
nonce store with `SiweLogin.registerNonceStore(VaadinContext, NonceStore)` when
the component may be deserialized outside the request that constructed it.
`SiweLogin` does not support EIP-1271 smart-contract wallet signatures yet.

### Send a transaction

```java
wallet.sendTransaction(
        "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
        Web3Utils.etherToWeiHex(new BigDecimal("0.05")),
        null)
    .thenAccept(hash -> getUI().get().access(() ->
            Notification.show("Submitted: " + hash)));
```

Arbitrary transaction fields (`gas`, `data`, ...) can be passed with
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

## Events

`Web3Connect` fires `Web3ConnectedEvent`, `Web3DisconnectedEvent`,
`ChainChangedEvent`, `TransactionSentEvent`, `MessageSignedEvent` and
`Web3ErrorEvent` (with the EIP-1193 error code; `isUserRejected()` maps
code 4001). Use `addProviderDetectedListener()` to observe
`ProviderDetectedEvent`, and `isProviderAvailable()` to check whether a wallet
provider is available. Failed futures use `Web3Connect.Web3Exception`; inspect
`getCode()` or `isUserRejected()` for the error details. `Web3Address` fires
`AddressCopiedEvent`.

### Disconnecting

After disconnecting, the add-on will not silently reconnect through `restore()`
or when the wallet changes accounts. It will attempt to call
`wallet_revokePermissions` when the wallet supports it.
Disconnect state applies per site and synchronizes with other instances on the same page and other tabs.

## Security notes

- The connected account address is client-reported. For authentication,
  always verify a signature server-side (e.g. [EIP-4361 Sign-In with
  Ethereum](https://eips.ethereum.org/EIPS/eip-4361) with a server-issued
  nonce) — never trust the bare address.
- Transactions are confirmed by the user in their wallet; the add-on never
  touches private keys.

## Project structure

- `addon/` — the add-on itself (`com.wontlost:vaadin-web3`)
- `server/` — server-side SIWE messages, nonce storage and signature verification (`com.wontlost:vaadin-web3-server`)
- `demo/` — a Spring Boot demo application exercising all features

## Development

```bash
mvn verify                      # build everything + unit tests
mvn install                     # install the add-on locally first
# Or install only the add-on and its required modules:
mvn -pl addon -am install
mvn install -Pdirectory -pl addon -am   # also builds the Vaadin Directory zip
mvn spring-boot:run -pl demo    # run the demo at http://localhost:8080
```

The SIWE demo is available at `/login`.

The demo includes `vaadin-dev` for Vaadin development mode.

The demo needs a browser wallet extension (e.g. MetaMask); use a test
network such as Sepolia when trying transactions.

## On-chain reads

The server module provides an `EthRpcClient` over either a JSON-RPC URL or a
custom `JsonRpcTransport`. `Erc20` builds common ERC-20 calls and reads
`balanceOf` / `decimals`; `Tokens.usdc(chainId)` contains the supported USDC
contracts and `Tokens.toBaseUnits()` converts display amounts without rounding.
Register clients in the application-scoped `ChainRegistry` and store it in the
`VaadinContext` before using token gates or payment verification:

```java
@Bean
VaadinServiceInitListener web3Chains() {
    ChainRegistry chains = new ChainRegistry();
    chains.register(11155111, "https://ethereum-sepolia-rpc.publicnode.com"); // Sepolia
    return event -> event.getSource().getContext().setAttribute(ChainRegistry.class, chains);
}
```

If no registry is registered, `@RequiresToken` views fail closed: they are
rejected as temporarily unavailable (HTTP 503) and a warning is logged.

## Token-gated views

Annotate a Vaadin route with `@RequiresToken` to require a wallet balance. The
automatic route listener checks the SIWE-verified address in `Web3Session` and
forwards unsigned visitors to the configured login route. Balances are cached
per browser tab for 30 seconds, so a holder who moves their tokens away keeps
access for at most that long. For example, the demo's `/holders` route requires
at least 1 Sepolia USDC. Test USDC can be claimed from the
[Circle testnet faucet](https://faucet.circle.com/).

## Stablecoin checkout

`StablecoinCheckout` asks the connected wallet to transfer USDC and verifies
the transaction receipt and Transfer logs against a `PaymentRequest`. The
demo's `/checkout` route requests 1.00 Sepolia USDC; replace
`web3.demo.recipient` in `application.properties` with the receiving wallet
address. A process-local ledger prevents one transaction from satisfying two
orders. The checkout uses the SIWE-verified address as the expected payer when
available; otherwise it uses the connected wallet account reported by the
client.
Receipt verification runs on a bounded background executor; the UI is updated
through `UI.access()` and the existing Vaadin polling cycle delivers the update
to the browser without requiring `@Push`. A confirmed checkout remains disabled
and displays “Paid”; call `reset(newOrderId)` to begin another order. A transfer
without a matching token and recipient is reported as `NO_MATCHING_TRANSFER`.

## License

Apache License 2.0
