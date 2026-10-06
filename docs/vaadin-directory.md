# Vaadin Directory listing

Use this text when publishing the add-on to https://vaadin.com/directory.

**Name:** Web3 Add-on for Vaadin

**Summary (one line):** Wallet login, token-gated views, stablecoin payments and card purchases for Vaadin Flow, in plain Java.

**Tags:** web3, ethereum, wallet, metamask, siwe, authentication, payments, stablecoin, usdc, blockchain, onramp, card, spring-boot

## Compatibility

| Java | Vaadin | Spring Boot | Spring Security |
|---|---|---|---|
| 21 | 25.3+ | 4.1.x for `web3-vaadin-spring-boot-starter` | Optional; add `spring-boot-starter-security` |

## Description

Bring Ethereum wallets to your Vaadin application without writing JavaScript. Use `com.wontlost:web3-vaadin-spring-boot-starter` for Spring Boot auto-configuration, or use the component and server modules directly.

- **Connect any wallet.** `Web3Connect` works with MetaMask, Coinbase Wallet, Rabby, Brave and other EIP-1193 providers. EIP-6963 discovery presents a built-in accessible picker.
- **Connect mobile wallets.** The optional `web3-vaadin-walletconnect` module adds WalletConnect v2, desktop QR codes and mobile app deep links. Configure a Reown project ID and supported chains.
- **Sign in with Ethereum.** `SiweLogin` verifies EIP-4361 messages and stores the verified identity in `Web3Session`. With optional Spring Security, `Web3SiweLoginConfigurer.configure(login)` also connects verified SIWE events to a persisted `Web3Principal`. The application configures its own `SecurityFilterChain`.
- **Gate views on token ownership.** Put `@RequiresToken(chainId = 1, token = "USDC", minBalance = "10")` on a route. Built-in USDC, USDT, EURC and PYUSD symbols are available on supported networks.
- **Take stablecoin payments.** `StablecoinCheckout` checks on-chain receipts and Transfer logs before confirming a payment. A `PaymentLedger` prevents one transaction from paying for multiple orders.
- **Buy stablecoins with a card.** The optional `web3-vaadin-onramp` module supports hosted MoonPay, Transak and Coinbase purchase flows.
- **Use a Development wallet locally.** `web3.dev.mock-wallet.enabled=true` exposes an Anvil-backed test wallet only in Vaadin development mode. Anvil's default keys are public; never use them with real assets. Production mode rejects the setting.
- **Test SIWE and checkout.** `web3-vaadin-test` supplies test wallet and RPC fixtures that exercise the normal verification paths.

The starter configures `ChainRegistry`, nonce store, payment ledger and verifier defaults. It does not create UI routes or a `SecurityFilterChain`. Spring Security is optional. Pure WebSocket push cannot persist the SIWE authentication cookie; use servlet-backed Vaadin push transport.

The core component module has no third-party dependencies. Sign-in, token gates and checkout are in `com.wontlost:web3-vaadin-server`.

## Quick start

See [the tutorial](tutorial.md) for Maven dependency, starter configuration, SIWE login, Spring Security and Sepolia checkout steps. The release artifact is `com.wontlost:web3-vaadin-spring-boot-starter` version `1.0.0`.

## Screenshot checklist

Add screenshots captured from the running demo under `docs/images/`:

- `docs/images/wallet-picker.png` — real wallet and Development wallet with its warning visible.
- `docs/images/siwe-login.png` — login page before signing, with development wallet clearly identified.
- `docs/images/protected-account.png` — authenticated account route using non-personal test identity data.
- `docs/images/sepolia-checkout.png` — checkout state on Sepolia without credentials. *(not yet captured: needs a funded Sepolia test wallet)*
- `docs/images/sepolia-payment-confirmed.png` — optional real testnet confirmation only; never use a fabricated success state. *(not yet captured)*

## Links

- Source and documentation: https://github.com/wontlost-ltd/vaadin-web3
- Tutorial: https://github.com/wontlost-ltd/vaadin-web3/blob/main/docs/tutorial.md
- Changelog: https://github.com/wontlost-ltd/vaadin-web3/blob/main/CHANGELOG.md
