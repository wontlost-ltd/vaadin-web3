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
| `Web3Connect` | `<web3-connect>` | Wallet connect/disconnect button + full wallet API |
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

### Sign a message (e.g. Sign-In with Ethereum)

```java
wallet.signMessage("Log in to Example at " + Instant.now())
        .thenAccept(signature -> Notification.show("Signature: " + signature));
```

The `CompletableFuture` callbacks run while the Vaadin session is locked, so no extra `UI.access()` call is needed.

EIP-712 typed data is supported through `wallet.signTypedData(jsonPayload)`.

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

The demo includes `vaadin-dev` for Vaadin development mode.

The demo needs a browser wallet extension (e.g. MetaMask); use a test
network such as Sepolia when trying transactions.

## License

Apache License 2.0
