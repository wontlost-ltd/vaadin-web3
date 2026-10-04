# Wallet login, token gates and USDC checkout for Vaadin, in plain Java

*Draft. Not yet published.*

Most web3 tutorials assume a React frontend, a JavaScript wallet library and a
separate backend to check what the frontend claims. Vaadin developers have a
simpler model: the server owns the UI. The Web3 Add-on for Vaadin brings
wallets into that model. This post shows the add-on through four small
examples. Each one is a few lines of Java.

## 1. Connect a wallet

```java
Web3Connect wallet = new Web3Connect();
wallet.addConnectedListener(e -> Notification.show("Connected " + e.getAccount()));
add(wallet);
```

The add-on talks to whatever wallet the browser injects. It has no bundled
web3 library, so the component module adds no dependencies to your
application. When a user has several wallets installed, for example MetaMask
and Rabby, the component finds them through EIP-6963 and shows a picker. It
remembers the choice for the next visit.

## 2. Sign users in with Ethereum

A connected address on its own proves nothing, because the browser reports
it. `SiweLogin` implements Sign-In with Ethereum (EIP-4361):

```java
SiweLogin login = new SiweLogin(NONCES).setDomain("example.com").setUri("https://example.com");
login.addSignedInListener(e -> Notification.show("Welcome " + e.getSignIn().address()));
```

The server does all the work:

- It issues a single-use nonce.
- It checks the domain, URI, chain and time window of the signed message.
- It recovers the signer and stores the verified identity in `Web3Session`.

Smart-contract wallets work too. Safe and Coinbase Smart Wallet are checked
on chain through ERC-1271. Wallets that haven't been deployed yet are checked
through ERC-6492. Our test suite checks the message parser against messages
produced by the reference `siwe` and `ethers` libraries.

## 3. Gate a view on token ownership

```java
@Route("members")
@RequiresToken(chainId = 1, token = "USDC", minBalance = "100")
public class MembersView extends VerticalLayout { }
```

That's the whole integration:

- Visitors who aren't signed in are sent to your login view and come back
  automatically afterwards.
- Visitors without enough balance get a 403 page.
- If the RPC node is down, visitors get a 503 page rather than a stack trace.

The check always uses the server-verified SIWE address. For NFTs, use the
contract address with `decimals = 0`.

## 4. Take a USDC payment

```java
StablecoinCheckout checkout = new StablecoinCheckout(chains, ledger, treasury, new BigDecimal("25.00"))
        .setOrderId(order.id());
checkout.addPaymentConfirmedListener(e -> orders.markPaid(order.id(), e.getResult().txHash()));
```

The component asks the wallet to sign a USDC `transfer`. It doesn't trust the
transaction hash the wallet returns. Instead it reads the receipt from the
chain, adds up the Transfer logs sent to your address, waits for the
confirmations you set, and claims the transaction in a ledger, so the same
transfer can't pay for two orders. USDC addresses for 11 networks, including
Ethereum, Base, Arbitrum, OP, Polygon and Avalanche, plus their testnets, are
built in.

## How it was tested

- **Real mainnet transactions**: the payment verifier was tested against real
  mainnet USDC transactions. One was a simple transfer. The other was an
  85-log DEX swap.
- **End-to-end in a browser**: the whole flow was run in a browser against a
  local chain:
  1. Wallet picker.
  2. SIWE sign-in.
  3. Gate redirect and return.
  4. The 403 page.
  5. A checkout through to "Paid".

## Try it

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>vaadin-web3-server</artifactId>
    <version>1.0.0</version>
</dependency>
```

The repository includes a Spring Boot demo with every feature wired up on the
Sepolia testnet. Next on the roadmap is WalletConnect for mobile wallets. Feedback and issues are
welcome.
