# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - Unreleased

First public release.

### Added — `vaadin-web3`
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

### Added — `vaadin-web3-server` (new module)
- Sign-In with Ethereum (EIP-4361):
  - `SiweMessage` builds messages and parses them strictly. It interoperates
    with `siwe` and `ethers`.
  - `SiweVerifier` and `InMemoryNonceStore`.
  - The `SiweLogin` component and `Web3Session`.
  - `SiweLogin` supports reverse proxies (`Forwarded` / `X-Forwarded-*`), sends
    users back to their `continue` page, and offers `signOut()`.
- On-chain reads: `EthRpcClient`, `Erc20`, `ChainRegistry`, and the `Tokens`
  USDC registry for 11 networks.
- `@RequiresToken` token-gated routes: checks use the SIWE-verified address,
  results are cached for 30 s, and 403/503 error views are included. Gated
  views fail closed when no `ChainRegistry` is configured.
- `StablecoinCheckout` and `PaymentVerifier`: USDC payments verified against
  the receipt and Transfer logs, with confirmation counting and a
  `PaymentLedger` that stops one transaction from paying for two orders.

### Demo
- Spring Boot demo with routes for wallet features, SIWE login, a token-gated
  view and a checkout.

[1.0.0]: https://github.com/wontlost-ltd/vaadin-web3/releases/tag/v1.0.0
