# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - Unreleased

### Fixed
- Rotate servlet session IDs after verified SIWE sign-in when the current Vaadin request supports it; warn when the transport cannot rotate the ID.
- Reject payment confirmation when the receipt block is no longer canonical, and prevent wallet restoration from racing with a cross-instance disconnect.
- Bound the token-gate balance cache and improve wallet-picker keyboard accessibility.

### Added
- Optional confirmation-count or finalized-block payment finality, localized wallet-picker/on-ramp labels, and finality/localization guidance.

Modules use the `web3-vaadin-*` naming (for example `com.wontlost:web3-vaadin`),
matching the other WontLost add-ons.

First public release.

### Added — `web3-vaadin-monitor`
- Optional Spring Boot hosted payment monitor with JDBC/Flyway persistence,
  H2 and PostgreSQL support, merchant/admin APIs, leased payment verification,
  signed retrying webhooks and webhook URL address checks.
- `PaymentMonitorClient` and `WebhookSignatures` in `web3-vaadin-server`;
  `StablecoinCheckout.setPaymentMonitor(true)` can keep tracking after the
  buyer leaves the page.
- Monitor API, deployment and webhook verification guide at `docs/MONITOR.md`.
- `StablecoinCheckout` never re-enables Pay once a transaction has been sent,
  even during a long network outage; it keeps verifying and tells the user not
  to pay again.

### Added — `web3-vaadin`
- `Web3Connect`: EIP-1193 wallet connection with connect, restore, disconnect
  (including `wallet_revokePermissions`), message and EIP-712 signing,
  transactions, and switching or adding chains. Calls return a
  `CompletableFuture`, and wallet state changes are reported as server-side
  events.
- EIP-6963 multi-wallet discovery: `getWallets()`, `connect(rdns)`, a preferred
  wallet, a built-in keyboard-accessible picker, and memory of the last wallet
  used.
- `Web3Address`: shortened address display with a colour badge and click to
  copy.
- `Chains` and `Web3Utils` helpers.

### Added — `web3-vaadin-walletconnect`
- Optional WalletConnect v2 mobile wallet support with EIP-6963 discovery,
  desktop QR codes, mobile app deep links, configured chain limits, and lazy
  provider loading.

### Added — `web3-vaadin-server` (new module)
- Sign-In with Ethereum (EIP-4361):
  - `SiweMessage` builds messages and parses them strictly. It interoperates
    with `siwe` and `ethers`.
  - `SiweVerifier` and `InMemoryNonceStore`.
  - The `SiweLogin` component and `Web3Session`.
  - `SiweLogin` supports reverse proxies (`Forwarded` / `X-Forwarded-*`), sends
    users back to their `continue` page, and offers `signOut()`.
- Smart-contract wallet sign-in:
  - Supports ERC-1271 for deployed wallets and ERC-6492 for wallets not yet
    deployed.
  - Verification is a single deployless `eth_call` through
    `SignatureValidator`.
  - It's enabled automatically when a `ChainRegistry` is registered.
  - A new `SIGNATURE_UNVERIFIABLE` reason reports RPC failures.
  - `NonceStore.isActive()` lets the verifier reject unknown nonces before
    making any RPC call.
- On-chain reads: `EthRpcClient`, `Erc20`, `ChainRegistry`, and the `Tokens`
  USDC registry for 11 networks.
- `@RequiresToken` token-gated routes: checks use the SIWE-verified address,
  results are cached for 30 s, and 403/503 error views are included. Gated
  views fail closed when no `ChainRegistry` is configured.
- `StablecoinCheckout` and `PaymentVerifier`: USDC payments by default, verified
  against the receipt and Transfer logs, with confirmation counting and a
  `PaymentLedger` that stops one transaction from paying for two orders.
  `PaymentRequest.notBefore` rejects transactions mined before the order was
  created (`PREDATES_ORDER`); the checkout sets it automatically, which stops
  replays of earlier transfers to the merchant (tolerance configurable,
  two minutes by default). `PaymentRequest` gains a record component, which
  changes its canonical constructor and record pattern; the earlier
  constructors remain.
- Built-in USDT, EURC and PYUSD contract registries; token gates accept
  built-in symbols, and checkout supports same-currency token selection.

### Added — `web3-vaadin-onramp` (new module)
- `OnrampProvider`, `OnrampOrder` and `FiatOnrampButton` with MoonPay, Transak and Coinbase hosted purchase sessions.
- Provider live currencies are matched against registered chain IDs and contract addresses; staging and sandbox match provider test tokens by symbol and chain.
- `StablecoinCheckout.setOnrampAction(...)` can show a card-purchase action below the checkout button.

### Demo
- Spring Boot demo with routes for wallet features, SIWE login, a token-gated
  view and a checkout.
