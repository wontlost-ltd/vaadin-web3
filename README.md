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

## Compatibility

| Java | Vaadin | Spring Boot | Spring Security |
|---|---|---|---|
| 21 | 25.3+ | 4.1.x (starter) | Optional; add `spring-boot-starter-security` |

The core Vaadin components do not require Spring Boot. The starter targets Spring Boot 4.1.x.

## Development wallet

The `demo` profile enables an Anvil-backed Development wallet for local use. Anvil's default accounts and private keys are public. Use only a local chain with no valuable assets. The starter rejects `web3.dev.mock-wallet.enabled=true` when Vaadin production mode is active.

## Spring Security

Add `spring-boot-starter-security`, configure a `SecurityFilterChain` with `VaadinSecurityConfigurer.vaadin()`, and pass each `SiweLogin` through `Web3SiweLoginConfigurer.configure(login)`. That applies the configured SIWE expectations and, when Spring Security is present, connects verified SIWE events to the persisted `SecurityContext`. Add the starter's `Web3LogoutHandler` with `addLogoutHandler(...)`; `SiweLogin.signOut()` also clears the Spring context through the event bridge.

The bridge needs a servlet request and response. A pure WebSocket push callback cannot persist the authentication cookie and fails closed; use Vaadin's `WEBSOCKET_XHR` or long-polling transport for SIWE authentication.

Declare ordered `SiweAuthoritiesResolver` beans to add authorities after verification. Each resolver receives the verified `Web3Principal`, `VerifiedSignIn` and servlet request; duplicate authorities are removed while preserving configured and resolver order:

```java
@Bean
@Order(10)
SiweAuthoritiesResolver tenantAuthorities(TenantDirectory tenants) {
    return context -> tenants.rolesFor(context.request().getHeader("X-Tenant"), context.principal())
            .stream().map(SimpleGrantedAuthority::new).toList();
}
```

For tenant-specific SIWE expectations, declare ordered `SiweLoginCustomizer` beans and call `configure(login, request)`. These run after the starter's configured domain, URI and allowed chains, and after existing `Web3SiweLoginCustomizer` beans. The existing `configure(login)` method remains available and passes `null` to request-aware customizers.

```java
@Bean
@Order(10)
SiweLoginCustomizer tenantSiweSettings(TenantDirectory tenants) {
    return (login, request) -> {
        if (request != null) {
            Tenant tenant = tenants.fromHost(request.getServerName());
            login.setDomain(tenant.siweDomain()).setUri(tenant.siweUri())
                    .setAllowedChainIds(tenant.allowedChainIds());
        }
    };
}
```

When Spring Security is present, the bridge publishes authentication success, bad-credentials failure and logout success events. `SiweLogin` already exposes a verification-failure event; resolver failures roll back the Vaadin session and publish a bad-credentials event with a fixed, non-sensitive reason.

## Quick start

Add the starter (currently install this repository locally with `mvn -B -ntp install`; use `1.0.0` after release):

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>web3-vaadin-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Configure the SIWE origin and an RPC endpoint (five lines):

```properties
web3.siwe.domain=localhost
web3.siwe.uri=http://localhost:8080
web3.chains.11155111.rpc-url=${SEPOLIA_RPC_URL:https://ethereum-sepolia-rpc.publicnode.com}
```

Create a login route using the starter's nonce store and configurer:

```java
@Route("login")
@AnonymousAllowed
public class LoginView extends VerticalLayout {
    public LoginView(NonceStore nonces, Web3SiweLoginConfigurer configurer) {
        add(configurer.configure(new SiweLogin(nonces)));
    }
}
```

### Observe JSON-RPC transport calls

The Spring Boot starter exposes `JsonRpcTransportDecorator` for integrations
that need to observe or wrap RPC sends. Declare one or more Spring beans; they
are applied in `@Order` order to each configured chain, including pinned reads
and multi-endpoint failover. Decorators must preserve request and response
semantics and return a non-null transport. The OSS modules do not depend on
Micrometer or any Pro module.

```java
@Bean
@Order(10)
JsonRpcTransportDecorator rpcObservation(RpcMetrics metrics) {
    return (chainId, transport) -> request -> {
        metrics.recordRequest(chainId);
        return transport.send(request);
    };
}
```

For a guided flow, see [the tutorial](docs/tutorial.md).

## What you get

| Feature | Module | Highlights |
|---|---|---|
| `Web3Connect` wallet button and API | `web3-vaadin` | Connect, restore, disconnect, sign, EIP-712, send transactions, switch or add chains |
| EIP-5792 calls API | `web3-vaadin` | Capability lookup, batch submission and status; sequential fallback only when explicitly allowed |
| Spring Boot auto-configuration | `web3-vaadin-spring-boot-starter` | Chain registry, nonce store, payment ledger and verifier defaults; optional SIWE Spring Security bridge |
| Test wallets and fixtures | `web3-vaadin-test` | Signed SIWE messages, test nonce store and JSON-RPC payment fixture |
| Multi-wallet discovery | `web3-vaadin` | EIP-6963 discovery, a keyboard-accessible picker, remembers the last wallet |
| `Web3Address` | `web3-vaadin` | Shortened address, colour badge, click to copy |
| WalletConnect mobile wallets | `web3-vaadin-walletconnect` | EIP-6963 discovery, desktop QR codes, mobile wallet deep links, lazy-loaded provider |
| `SiweLogin` | `web3-vaadin-server` | EIP-4361 messages, single-use nonces, ordinary and smart-contract wallets (ERC-1271, ERC-6492), `Web3Session` |
| `@RequiresToken` | `web3-vaadin-server` | ERC-20/ERC-721 balance gates, redirect to login with a continue link, ready-made 403/503 pages |
| `StablecoinCheckout` | `web3-vaadin-server` | USDC, USDT, EURC or PYUSD transfer, receipt and Transfer-log checks, confirmations, protection against reusing one transaction for two orders; optional hosted monitoring |
| `PaymentMonitorClient`, `WebhookSignatures` | `web3-vaadin-server` | Connect a checkout to the hosted monitor and verify timestamped HMAC webhooks |
| Hosted payment monitor service | `web3-vaadin-monitor` | Continuously verifies intents using configured RPC endpoints and retries signed merchant webhooks; executable Spring Boot service |
| `FiatOnrampButton` | `web3-vaadin-onramp` | Hosted card purchases through MoonPay, Transak or Coinbase with registered contract matching and popup fallback |
| `EthRpcClient`, `Erc20`, `Tokens` | `web3-vaadin-server` | Minimal JSON-RPC client, ERC-20 calls, built-in stablecoin contract addresses |
| `NetworkIndicator` | `web3-vaadin` | Wallet chain status and optional switch/add-chain action |
| `Balance`, `TransactionStatus`, `UiPolling` | `web3-vaadin-server` | Server RPC balances and transaction finality with coordinated UI polling |
| Multi-endpoint RPC and health | `web3-vaadin-spring-boot-starter` | Ordered failover, circuit breakers and optional Actuator health indicator |

### x402 v2 payment core

The server module includes the x402 v2 `exact` payment core for EVM networks using EIP-3009 `transferWithAuthorization`: strict Base64/JSON codecs, facilitator verification and settlement, and a payment state store. HTTP clients construct their own authorization from `PAYMENT-REQUIRED`, as standard x402 v2 clients do; the Vaadin paywall uses server-generated intents through `prepare`. The Spring Boot integration is opt-in with `web3.x402.enabled=true`; configure a canonical `web3.x402.origin` and an HTTPS facilitator URL. When an RPC is configured for the payment network, authorization time windows use the latest block timestamp. Each `prepare` and READY-state authorization check reads the latest block once. If no RPC is configured for that network (for example, when a remote facilitator settles payments and the application has no local RPC), the server clock is used instead; `web3.x402.valid-after-skew` defaults to 600 seconds before that clock to tolerate timestamp differences. `web3.x402.reconcile-interval` is disabled by default; set a positive duration such as `PT1M` to periodically reconcile pending outcomes. `web3.x402.reconcile-confirmations` defaults to 3 and accepts 0; reconciliation evaluates authorization state and expiry at one confirmed block snapshot and waits for transaction receipts to reach the same confirmation depth. Reconciliation never rebroadcasts settlement. A `SETTLING` record is recovered only after the facilitator request timeout plus a 10-second margin. The default store is in-memory and loses payment state on restart; Vaadin production mode rejects it unless `web3.x402.allow-in-memory-store=true`. Production deployments should provide a persistent `PaidResourceStore` (the Pro edition provides JDBC).

### EVM NFT ownership

The server module exposes `NftOwnershipSource` and a direct RPC implementation for configured ERC-721 and ERC-1155 collections. Enable it with `web3.nft.enabled=true`, then configure each collection under `web3.nft.collections` with its `chain-id`, `contract`, `standard`, optional `enumerable` flag, and explicit `token-ids` or inclusive `ranges`. The source reports balances at a pinned block snapshot, supports cursor pagination, and limits page size, batch size, token count, RPC concurrency, and the executor queue. NFT cursors are opaque continuation values, not tamper-proof tokens. Applications can replace the default source with their own `NftOwnershipSource` bean.

#### NFT metadata

When `web3.nft.enabled=true`, the starter also registers a replaceable `NftMetadataResolver`. It reads ERC-721 `tokenURI` and ERC-1155 `uri` values and returns bounded text fields and attributes without exposing the source JSON. ERC-1155 follows [EIP-1155](https://eips.ethereum.org/EIPS/eip-1155): only the lowercase `{id}` placeholder is expanded to a 64-character lowercase hexadecimal token ID. IPFS URIs are rewritten to configured `web3.nft.metadata.ipfs-gateways` HTTPS gateways in order; the next gateway is tried only after a transport failure or timeout, not after a content-type, size, or JSON error.

The default fetcher uses Apache HttpClient 5 with redirects disabled at the transport layer and a custom DNS resolver. The resolver checks all DNS answers before calling the fetcher, then Apache's resolver independently checks every answer again when opening the connection. The second lookup is not pinned to the first result; a private or reserved answer at connection time is rejected as `UNSAFE_TARGET`. Redirects are followed manually and validated again, with a default maximum of three. Only HTTPS targets, allowed ports (443 by default), public IP addresses, JSON content types (`application/json` or `application/*+json`), and bounded response bodies are accepted. `text/plain` is rejected because the [OpenSea metadata guidance](https://docs.opensea.io/docs/metadata-standards) describes JSON documents and does not specify that content type as acceptable. Data URIs are limited before and after decoding. Image links allow HTTPS or IPFS only; SVG images are retained as metadata but marked non-displayable. `animation_url` is retained as text and is not rendered by the resolver. Applications providing a custom `NftMetadataFetcher` must apply `PublicAddressPolicy` to every address resolved at connection time; validation only before the fetch call is insufficient.

Key settings include `web3.nft.metadata.request-timeout`, `max-response-bytes`, `max-data-uri-bytes`, `max-uri-length` (8,192 characters by default), `max-redirects`, `cache-capacity`, `positive-ttl`, `negative-ttl`, `error-ttl`, `max-concurrency`, and `allowed-ports`. The URI limit applies to non-data schemes; `data:` URIs use `max-data-uri-bytes` for their encoded and decoded payload bounds and may therefore exceed 8,192 characters. Oversized decoded URIs return `URI_TOO_LONG` before ERC-1155 template expansion or fetching. The metadata cache is bounded and uses separate TTLs for successful, missing-token, and error results.

#### NFT gallery UI

`NftGallery` is a read-only Vaadin component backed by `NftOwnershipSource`, `NftMetadataResolver`, and configured collections. It reads the verified address from `Web3Session` under the Vaadin session lock, shows a sign-in link without querying for anonymous users, and clears gallery state when the component is reattached or the identity changes. It subscribes to `SiweLogin` events found in the same UI and removes those listeners on detach. When an application changes SIWE identity in place without a `SiweLogin` event, call `refresh()` after sign-in or sign-out. The demo sign-out navigates to the login route, which detaches the gallery. Ownership and metadata work runs off the UI thread; the gallery uses explicit keyboard accessible “Load more” paging, a 500-item default cap, skeleton cards, localized partial failure messages, retry controls, metadata placeholders, and an accessible live status. External metadata is inserted as text. Only the resolver's displayable HTTPS image URL is used for card images; SVG, data, and unsafe sources show placeholders. The details dialog renders descriptions and attributes as text, and only HTTPS `external_url` values become links.

The demo profile enables `web3.nft.enabled=true` and exposes `/nfts`. On startup it deploys the enumerable ERC-721 and ERC-1155 test fixtures when chain 31337 is reachable, mints sample tokens to the development wallet, then fills `web3.nft.collections` with the deployed contract addresses. Sample metadata uses local `data:application/json` URIs; raster artwork uses a public HTTPS image URL, while SVG and data image entries demonstrate safe placeholders. If Anvil is unavailable, the demo still starts and `/nfts` reports that ownership data is unavailable. Start Anvil on port 8547 and run the demo with `ANVIL_RPC=http://127.0.0.1:8547 mvn spring-boot:run -pl web3-vaadin-demo -Dspring-boot.run.profiles=demo`. The mint and URI setter functions in these mock contracts are for tests and demonstrations only.

The test kit includes minimal ERC-721 and ERC-1155 fixtures. Their public mint functions are for tests only. Recompile the Solidity artifacts with Foundry 1.5.1 and solc 0.8.28 from the repository root:

```sh
forge build --root "$PWD/web3-vaadin-test/src/main/resources/contracts" --contracts "$PWD/web3-vaadin-test/src/main/resources/contracts" --use 0.8.28 --out /tmp/nft-forge-out --cache-path /tmp/nft-forge-cache
python3 - <<'PY'
import json
from pathlib import Path

output = Path("/tmp/nft-forge-out")
contracts = Path("web3-vaadin-test/src/main/resources/contracts")
for name in ("Nft721Mock", "Nft1155Mock"):
    artifact = json.loads((output / f"{name}.sol" / f"{name}.json").read_text())
    result = {"contractName": name, "abi": artifact["abi"], "bytecode": artifact["bytecode"]["object"]}
    target = contracts / f"{name[0].lower()}{name[1:]}-bytecode.json"
    target.write_text(json.dumps(result, separators=(",", ":")) + "\n")
PY
```

### HTTP x402 resources

The HTTP payment filter is disabled by default. Enable it with `web3.x402.http.enabled=true`, then register protected routes with `@RequiresPayment(resource="resource-id")` or `web3.x402.http.resources`. The matching `ResourcePolicy` supplies the payment requirement. Only `GET` and `HEAD` routes are accepted by default; other methods require an explicit idempotent declaration. The filter buffers the protected response up to `web3.x402.http.max-response-bytes`, verifies the authorization before calling the handler, settles after the handler succeeds, and releases the response only after settlement. A `PENDING` settlement maps to HTTP 202 with `PAYMENT-RESPONSE.errorReason=settlement_pending`; an `UNKNOWN` result maps to HTTP 503 with `errorReason=settlement_unknown`. These status codes are implementation transport choices, not x402 protocol requirements. Both outcomes discard the protected response body. `PAYMENT-RESPONSE` uses the protocol fields `success`, `errorReason`, `errorMessage`, `payer`, and `transaction`; it does not add a `pending` field. Replaying an already-settled authorization reruns the idempotent protected handler and returns its response with the original successful payment receipt, without settling again. Configure SIWX with `requireSiwx=true` on a route when browser clients need a one-time CAIP-122 identity proof; its signed `resources` list must contain the protected resource URL. SIWX challenges use a single atomic consume store, separate from SIWE login nonce consumption. The in-memory challenge store defaults to a 10,000-entry limit (`web3.x402.http.siwx-challenge-capacity`) and rejects new challenges with HTTP 503 when full, without evicting live challenges. Authenticated SIWE principals can access resources they have already paid for.

The browser module exports `x402Fetch(input, init, options)`. Supply a wallet adapter with `account`, `chainId`, and `signTypedData(typedDataJson)`. By default it constructs an EIP-3009 authorization and EIP-712 data from `PAYMENT-REQUIRED`, including a random 32-byte nonce and a bounded authorization window; `createAuthorization({ requirement, account, chainId })` remains an optional override. This makes it compatible with standard x402 v2 clients that construct their own authorization rather than calling a server prepare endpoint. It signs once and retries once using the effective `Request` after applying `init`. A 2xx retry response must include a successful `PAYMENT-RESPONSE`; otherwise `x402Fetch` throws `X402_SETTLEMENT_UNCONFIRMED` and attaches the response for inspection. HTTP 202/503 pending or unknown outcomes use the same error code and remain distinguishable through status and `errorReason`. Request bodies must be replayable or an explicit `replayRequest` callback must recreate them. A challenge requiring SIWX can be handled by the optional `siwxProvider` callback. The demo profile exposes a separate `/x402-api` page and `/api/x402/quote` route for this HTTP flow; it is independent of the Vaadin `/paid-article` paywall. The Vaadin paywall continues to use the server-generated authorization flow.

Applications using Spring Security must permit the protected HTTP API paths so that the x402 filter can return its 402 challenge; authenticated identities remain available because the filter is ordered immediately after Spring Security. For non-GET API paths, configure CSRF explicitly for the application's request model. The demo permits `/api/x402/**`; its quote endpoint is a GET and requires no SIWE login. For non-GET APIs, configure CSRF for the application request model before enabling the payment filter.

The current implementation supports EVM `eip155` networks and 65-byte EOA signatures. It does not implement Permit2, ERC-7710, or smart-account signatures. The test kit includes a local EIP-3009 token and Anvil facilitator for valueless development chains. Public facilitators have not been tested for interoperability; treat their configuration as unverified until tested against the facilitator you intend to use.

The token bytecode in `web3-vaadin-test` was built with Foundry 1.5.1 and solc 0.8.28. From the repository root, compile with `forge build --root "$PWD/web3-vaadin-test/src/main/resources/contracts" --contracts "$PWD/web3-vaadin-test/src/main/resources/contracts" --use 0.8.28 --out /tmp/x402-forge-out --cache-path /tmp/x402-forge-cache`.

The `web3-vaadin` component module has **no third-party dependencies**. The
`web3-vaadin-server` module adds `org.web3j:crypto` for signature
verification.

## Wallet components (`web3-vaadin`)

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
    <artifactId>web3-vaadin-walletconnect</artifactId>
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
WEB3_WALLETCONNECT_PROJECT_ID=<your project id> mvn spring-boot:run -pl web3-vaadin-demo
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

## Localization

Components expose serializable `*I18n` objects with English defaults and chainable setters. For example:

```java
Web3Connect wallet = new Web3Connect();
wallet.setI18n(new Web3ConnectI18n()
        .setConnect("Connect")
        .setPickerTitle("Choose your wallet")
        .setNoWallets("No compatible wallets were found."));
```

Existing text setters remain available; the most recently applied text or i18n setting controls the visible label.

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

## Server features (`web3-vaadin-server`)

Spring Boot applications should use `web3-vaadin-spring-boot-starter` for default beans and Vaadin context registration. For non-Spring Boot applications, register a `ChainRegistry` and `NonceStore` yourself as described in [On-chain reads](#on-chain-reads) and [SIWE](#sign-in-with-ethereum-siwe).

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

`VerifiedSignIn.account()` returns the identity as a chain-neutral CAIP-10 `ChainAccount` (for example `eip155:1:0xAbC…`). `Web3Session.currentIdentity()` returns the signed-in identity of any chain, while `Web3Session.current()` returns only an EVM (SIWE) sign-in, so EVM-specific code keeps working unchanged and treats a non-EVM session as signed out. `ChainAccount.sameAccount` compares EVM addresses case-insensitively and other namespaces (such as base58 Solana addresses) exactly. `TokenGate` denies non-EVM identities before making any RPC call.

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

SIWE rotates the underlying HTTP session ID after successful verification when the callback runs on a servlet request. Use Vaadin's default `WEBSOCKET_XHR` or long-polling transport for this behavior. With a pure WebSocket push callback, rotation cannot set a response cookie; the component logs a warning and completes sign-in, so the application can disable component rotation with `setSessionIdRotation(false)` and perform its own session handling.

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

### Reliable RPC (multiple endpoints)

Configure endpoints in priority order with `web3.chains.<chainId>.rpc-urls`. The
list takes precedence over `rpc-url`; `rpc-url` remains supported for one
endpoint, as does the legacy `rpc.<chainId>` property. If multiple forms are
configured, the starter logs a conflict warning.

```properties
web3.chains.11155111.rpc-urls[0]=https://primary.example/rpc
web3.chains.11155111.rpc-urls[1]=https://backup.example/rpc
web3.rpc.failure-threshold=3
web3.rpc.open-duration=15s
web3.rpc.request-timeout=10s
web3.rpc.lag-tolerance=3
```

Defaults are three consecutive failures before a circuit opens, a 15 second
open period, a 10 second HTTP request timeout and three blocks of tolerated
head lag. Requests stay on the successful endpoint. I/O failures, timeouts,
HTTP 408/429/5xx and recognized transient-node/rate-limit JSON-RPC errors can
move a request to another endpoint. Deterministic errors such as reverts,
insufficient funds, invalid requests and ordinary HTTP 4xx responses do not
trigger endpoint switching. The primary endpoint is probed for recovery and
becomes active at a subsequent request boundary.

`EthRpcClient.pinned()` returns a view bound to one endpoint for an operation;
it propagates endpoint failures without silently moving that operation to a
different node. For block-height consistency, the client tracks the highest
observed head and rejects an endpoint that trails it by more than
`lag-tolerance`. A failed `eth_sendRawTransaction` can be retried against up to
three configured endpoints using the same signed transaction bytes; it is
never re-signed. An `already known`, `known transaction` or `nonce too low`
response is treated as success only if the locally calculated transaction
hash can be found on chain.

When Spring Boot Actuator is present, the starter contributes a health
indicator. Overall health is `DOWN` if any configured chain has all monitored
endpoints open; it is `UP` when no chain meets that condition. No configured
chain produces `UNKNOWN`.
Health details redact credentials, query strings and long path keys. A
single-endpoint chain has no circuit-breaker snapshot and is reported as
`UNMONITORED`; this does not assert that the endpoint is healthy. The optional
health configuration is linked to the `HealthIndicator` class and does not
require Actuator in applications that omit it. The hosted payment monitor has
its own RPC configuration; see the [monitor guide](docs/MONITOR.md).

### Transaction status, balance and network components

`NetworkIndicator` in `web3-vaadin` observes `Web3Connect` and can offer a
switch to an expected chain. `Balance` and `TransactionStatus` are in
`web3-vaadin-server`: they read through the Vaadin-context `ChainRegistry`,
display a native or registered ERC-20 balance, and track receipts through the
selected `Finality`. `TransactionStatus.track(hash)` starts at submission and
reports pending, confirmation count, confirmed, failed receipt, or unknown
after its timeout. Explorer links are shown only for registered chains.

```java
Web3Connect wallet = new Web3Connect();
NetworkIndicator network = new NetworkIndicator(wallet);
network.setExpectedChainId(11155111);
Balance balance = new Balance().setChainId(11155111).setAddress(account)
        .setRefreshInterval(Duration.ofSeconds(15)); // Native currency
Balance usdc = new Balance().setChainId(11155111).setAddress(account).setToken("USDC");
TransactionStatus transaction = new TransactionStatus().setChainId(11155111)
        .setFinality(Finality.confirmations(1));
transaction.track(transactionHash);
```

Both server components use `UiPolling` to coordinate one poll interval per
Vaadin `UI`; the shortest active subscription controls that interval. They do
not require `@Push`. Detaching a component releases its subscription, and
`TransactionStatus` retries temporary RPC read failures while it remains
attached.

### Batch calls (EIP-5792)

`Web3Connect.getCapabilities()` (or `getCapabilities(List<Long>)`),
`sendCalls(CallsRequest, FallbackPolicy)`, `getCallsStatus(id)` and
`showCallsStatus(id)` expose the wallet batch methods. Calls use `Call`,
`CallsRequest`, `CallsSubmission`, `CallsStatus` and `WalletCapabilities` from
`com.wontlost.web3.calls`.

`CallsRequest` serializes the EIP-5792 version `2.0.0` and hexadecimal chain
ID. `WalletCapabilities.atomicByChain()` maps each requested chain key to
`SUPPORTED`, `READY`, `UNSUPPORTED` or `ABSENT`; an absent entry means no atomic
capability was advertised.

```java
CallsRequest request = new CallsRequest(null, wallet.getAccount(), 11155111, false,
        List.of(new Call(recipientA, null, valueA), new Call(recipientB, null, valueB)), null);
wallet.getCapabilities(List.of(11155111L));
wallet.sendCalls(request, FallbackPolicy.NEVER).thenAccept(submission ->
        wallet.getCallsStatus(submission.id()));
```

`FallbackPolicy.NEVER` is the default safe choice. Only the explicit
`ALLOW_NON_ATOMIC` option can fall back to sequential `eth_sendTransaction`
calls, and only when the wallet reports method unsupported (`4200` or
`-32601`) and `atomicRequired` is false. It is refused for atomic requests,
user rejection (`4001`), authorization errors (`4100`), invalid parameters
(`-32602`), EIP-5792 errors and a changed chain or account. `CallsSubmission`
marks whether fallback occurred and carries transaction hashes and per-call
errors.

The Development wallet reports atomic batching as unsupported. It rejects an
`atomicRequired` request with `5760`; non-atomic calls execute in order and
`wallet_getCallsStatus` reports `100` pending, `200` confirmed, `500` fully
reverted, or `600` partially reverted, with receipts and `atomic=false`.
It uses `400` only when an off-chain failure occurs before any transaction is
sent; status `400` is terminal and must not be retried. If a transaction was
already sent before a later failure, the wallet keeps the batch pending until
receipts arrive, then reports `600`. Unknown batch IDs return `5730`; duplicate
application IDs return `5720`.
Development-wallet batch records live only in memory and are lost on restart.

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

The optional `web3-vaadin-onramp` module opens hosted card-purchase sessions with
MoonPay, Transak or Coinbase. Add it alongside `web3-vaadin-server`:

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>web3-vaadin-onramp</artifactId>
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

#### Finality and reorgs

The default payment policy requires one confirmation for compatibility. A receipt can still be reorganized from the canonical chain at that depth, so applications should choose a stronger policy when the value or consequences of a payment justify it. Use `setMinConfirmations(n)` for a confirmation count, or `setFinality(Finality.finalized())` when the RPC node supports the `finalized` block tag. The checkout rechecks the receipt block hash against the canonical block before confirmation. Hosted monitoring currently supports confirmation counts only.

Confirm finality guidance with the official documentation for each chain you support. L1s, rollups and sidechains can have different settlement and reorganization properties; do not assume one confirmation count is suitable across them.

#### Hosted payment monitor

Use hosted monitoring when payment confirmation must continue after the buyer closes the checkout page, or when merchants should not operate their own chain RPC polling. The separate `web3-vaadin-monitor` service watches the chain through RPC, stores payment state in JDBC, and retries signed webhooks. It never holds funds or private keys.

Webhook DNS checks use the shared public-address policy, which also rejects IANA documentation, benchmarking, and other reserved ranges in addition to private, loopback, link-local, and multicast addresses. This is stricter than a private-range-only check. `monitor.webhooks.allow-private-targets` remains available for isolated local testing.

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
anvil                                      # in another terminal
mvn spring-boot:run -pl web3-vaadin-demo -Dspring-boot.run.profiles=demo
```

| Route | Shows |
|---|---|
| `/` | Wallet connection, EIP-6963 wallets, signing, transactions, chain switching |
| `/login` | Sign-In with Ethereum |
| `/holders` | A view gated on 1 Sepolia USDC |
| `/checkout` | A 1.00 Sepolia USDC or PYUSD checkout |
| `/paid-article` | SIWE-bound x402 EIP-3009 paid article; redirects to `/paywall` until settled |
| `/x402-api` | Browser `x402Fetch` example for the protected `/api/x402/quote` HTTP endpoint |

The demo profile provides a Development wallet for local SIWE. Anvil accounts are publicly known test keys and must never be used with valuable assets. The default demo checkout uses Sepolia and a real wallet. For a local fork checkout, plain Anvil is insufficient: its default chain ID 31337 has no built-in Sepolia token entries. See the tutorial for the Sepolia fork command and the matching local RPC override. You can get test USDC from the
[Circle faucet](https://faucet.circle.com/), and test PYUSD from the
[Paxos faucet](https://faucet.paxos.com/). Before trying the checkout, set
`web3.demo.recipient` in `web3-vaadin-demo/src/main/resources/application.properties` to
an address you control. The demo profile also enables the local x402 example. With Anvil on port 8545, the application deploys the `web3-vaadin-test` EIP-3009 token, mints test units to the Development wallet, and uses `LocalFacilitator` for settlement. Sign in at `/login` with the Development wallet before visiting `/paid-article`; only that verified SIWE address can pay and unlock the article. The payment guard reads `@RequiresPayment` from the routed view class only; it does not inspect parent layouts. Set `web3.x402.demo.deploy-on-startup=false` to skip deployment. If Anvil is unavailable, startup still succeeds and `/paywall` explains that payment is unavailable until the local chain is started. `LocalFacilitator` is restricted to the `demo` profile and is rejected in Vaadin production mode.

The HTTP example is separate from the Vaadin paywall. In the demo profile the filter protects `GET /api/x402/quote`; visit `/x402-api`, connect the Development wallet (SIWE login is not required), and select **Request protected quote**. SIWE login is not required for this anonymously payable resource. `x402Fetch` builds the authorization from the 402 challenge, signs it through `Web3Connect`, and retries the quote request once. The Vaadin `/paid-article` paywall still requires SIWE and uses a server-generated typed-data intent. Anvil's latest block time must stay reasonably aligned with the host clock; restart a long-running local Anvil instance if `evm_increaseTime` has moved its clock forward, so fresh authorizations pass the on-chain time-window check.

## Project structure

- `web3-vaadin/`: wallet components (`com.wontlost:web3-vaadin`), with no
  third-party dependencies.
- `web3-vaadin-walletconnect/`: optional WalletConnect v2 mobile wallet integration
  (`com.wontlost:web3-vaadin-walletconnect`).
- `web3-vaadin-server/`: SIWE, on-chain reads, token gates and checkout (`com.wontlost:web3-vaadin-server`).
- `web3-vaadin-onramp/`: hosted fiat-to-stablecoin purchases (`com.wontlost:web3-vaadin-onramp`).
- `web3-vaadin-monitor/`: optional hosted payment tracking service (`com.wontlost:web3-vaadin-monitor`).
- `web3-vaadin-spring-boot-starter/`: Spring Boot auto-configuration and optional SIWE Spring Security bridge.
- `web3-vaadin-test/`: test wallets and SIWE test fixtures.
- `web3-vaadin-demo/`: a Spring Boot demo application.

## Pro

The add-on is and stays Apache 2.0. For teams taking web3 features into
production, WontLost is preparing **Web3Vaadin Pro**, a set of commercial
modules on top of it:

- **Cluster storage**: JDBC-backed SIWE nonces and payment claims, so sign-in and
  payment verification work across several application nodes.
- **Compliance screening**: sanctions screening of wallet addresses with an
  audit trail of every decision.
- **Payment back office**: payment records, a filterable Vaadin view and CSV
  export.

These modules are not part of this repository. `AddressScreening` in
`web3-vaadin-server` is an open-source extension point:
applications can plug in their own screening without Pro.

Inquiries: [service@wontlost.com](mailto:service@wontlost.com).

## Development

```bash
mvn verify                               # build everything and run the unit tests
(cd web3-vaadin && npm ci && npm test)         # frontend unit tests (Vitest)
(cd web3-vaadin-walletconnect && npm ci && npm test) # WalletConnect frontend unit tests
mvn install -Pdirectory -pl web3-vaadin -am    # also builds the Vaadin Directory zip
```

See [CHANGELOG.md](CHANGELOG.md) for release notes.

## License

Apache License 2.0
