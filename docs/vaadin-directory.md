# Vaadin Directory listing

Use this text when publishing the add-on to https://vaadin.com/directory.

**Name:** Web3 Add-on for Vaadin

**Summary (one line):** Wallet login, token-gated views, stablecoin payments and card purchases for Vaadin Flow, in plain Java.

**Tags:** web3, ethereum, wallet, metamask, siwe, authentication, payments, stablecoin, usdc, blockchain, onramp, card

**Compatibility:** Vaadin 25.3+, Java 21+

## Description

Bring Ethereum wallets to your Vaadin application without writing JavaScript.

- **Connect any wallet.** `Web3Connect` works with MetaMask, Coinbase Wallet,
  Rabby, Brave and any other EIP-1193 provider. When several wallets are
  installed, it finds them through EIP-6963 and shows a built-in, accessible
  picker.
- **Connect mobile wallets.** The optional `vaadin-web3-walletconnect` module
  adds WalletConnect to the same picker, with desktop QR codes and mobile app
  deep links. It requires a Reown project ID and supports the configured chains.
- **Sign in with Ethereum.** `SiweLogin` follows EIP-4361. The server issues
  single-use nonces, recovers the signature and stores a verified identity in
  `Web3Session`.
- **Gate views on token ownership.** Put
  `@RequiresToken(chainId = 1, token = "USDC", minBalance = "10")` on a route.
  Built-in USDC, USDT, EURC and PYUSD symbols are supported alongside contract
  addresses.
  Visitors who aren't signed in go to your login view and come back
  afterwards. Visitors without enough balance see a 403 page.
- **Buy stablecoins with a card.** The optional `vaadin-web3-onramp` module creates hosted MoonPay, Transak and Coinbase purchase sessions. Live offerings are checked against the registered chain and contract address; test environments may deliver provider test tokens that `StablecoinCheckout` will not accept as payment.
- **Take stablecoin payments.** `StablecoinCheckout` accepts USDC, USDT, EURC
  and PYUSD on their supported built-in networks. Select multiple tokens that
  use the same currency, and it asks the wallet to transfer the selected token.
  It reports success only after it has checked the
  on-chain receipt, the Transfer logs and the number of confirmations. A
  ledger stops one transaction from paying for two orders.

Sign-in, token gates and checkout are in the companion artifact
`com.wontlost:vaadin-web3-server`.

Everything is server-side Java with `CompletableFuture` APIs and normal Vaadin
events. The component module has no third-party dependencies.

## Links

- Source and documentation: https://github.com/wontlost-ltd/vaadin-web3
- Changelog: https://github.com/wontlost-ltd/vaadin-web3/blob/main/CHANGELOG.md
