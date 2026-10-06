# Build a Vaadin Web3 application

This tutorial walks from an empty Vaadin and Spring Boot application to SIWE login, a protected view and a Sepolia stablecoin checkout. Public dependencies and examples below target Java 21, Vaadin 25.3+ and Spring Boot 4.1.x.

The starter release version in these examples is `1.0.0`. While this project is under development, build and install the modules locally from the repository root:

```bash
mvn -B -ntp install
```

## 1. Create an empty Vaadin application

Create a Spring Boot 4.1.x application with Vaadin 25.3+, Java 21 and the Vaadin Spring Boot starter. Confirm the empty application starts:

```bash
./mvnw spring-boot:run
```

Open <http://localhost:8080>. The Vaadin welcome page should load.

## 2. Add the Web3Vaadin starter

Add the dependency to your application `pom.xml`:

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>web3-vaadin-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Set the SIWE origin and an RPC URL in `src/main/resources/application.properties`:

```properties
web3.siwe.domain=localhost
web3.siwe.uri=http://localhost:8080
web3.chains.11155111.rpc-url=${SEPOLIA_RPC_URL:https://ethereum-sepolia-rpc.publicnode.com}
```

The starter supplies a `NonceStore`, `PaymentLedger`, `PaymentVerifier` and `ChainRegistry` unless your application provides replacements. It registers those services with the Vaadin context. It does not create a login view or a security filter chain for you.

Use the injected nonce store and configurer in your login route:

```java
@Route("login")
@AnonymousAllowed
public class LoginView extends VerticalLayout {
    public LoginView(NonceStore nonces, Web3SiweLoginConfigurer configurer) {
        SiweLogin login = configurer.configure(new SiweLogin(nonces));
        add(login);
    }
}
```

The configurer applies the configured domain, URI, allowed chain IDs and maximum message age. Spring Security's SIWE bridge is attached by the same call when Spring Security is on the classpath.

## 3. Run the demo profile with Anvil and sign in

Install [Foundry](https://book.getfoundry.sh/getting-started/installation), then start a local Anvil node in one terminal:

```bash
anvil
```

In another terminal, run the demo using the `demo` profile:

```bash
mvn spring-boot:run -pl web3-vaadin-demo -Dspring-boot.run.profiles=demo
```

Open <http://localhost:8080/login> and select **Development wallet**. A successful SIWE sign-in displays the verified account and chain. Anvil's default test accounts and private keys are public; use them only on a local chain without valuable assets. The starter fails at startup if this wallet is enabled in Vaadin production mode.

## 4. Protect a view with Spring Security

Add Spring Security to the application:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

Define the Vaadin security filter chain and include the Web3 logout handler:

```java
@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, Web3LogoutHandler web3LogoutHandler)
            throws Exception {
        http.with(VaadinSecurityConfigurer.vaadin(), configurer -> configurer
                .loginView(LoginView.class)
                .addLogoutHandler(web3LogoutHandler));
        return http.build();
    }
}
```

Mark public routes with `@AnonymousAllowed`. Mark a route requiring any authenticated identity with `@PermitAll`:

```java
@Route("account")
@PermitAll
public class AccountView extends VerticalLayout {
    public AccountView() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Web3Principal wallet = (Web3Principal) authentication.getPrincipal();
        add(new Paragraph(wallet.address()), new Paragraph("Chain " + wallet.chainId()));
    }
}
```

An unauthenticated visit to `/account` is redirected to `/login`. After SIWE, `?continue=account` returns the user to that view. The demo's shared layout shows the Account link and **Sign out** only to authenticated users. Sign out clears the Vaadin identity and persists an empty Spring Security context. The login component also exposes `signOut()`, whose signed-out event clears the Spring context through the configured bridge. `Web3LogoutHandler` handles the same Vaadin cleanup for Spring Security logout.

`@RequiresToken` remains a separate route check: it redirects an unsigned visitor to login, then returns 403 for insufficient token balance or 503 when the RPC check is unavailable. A pure WebSocket push callback cannot persist the Spring Security session cookie; use Vaadin's `WEBSOCKET_XHR` or long-polling transport for the SIWE bridge.

## 5. Connect a real wallet and receive Sepolia payments

For a real wallet, disable the demo profile and connect a wallet configured for Sepolia. The checkout uses the built-in Sepolia USDC and PYUSD contracts, and verifies the receipt and transfer logs before showing a confirmed payment.

Configure the recipient address in `web3.demo.recipient`, then fund the wallet with Sepolia test assets from the [Circle faucet](https://faucet.circle.com/) or [Paxos faucet](https://faucet.paxos.com/). Do not use mainnet funds for this tutorial.

The demo uses `StablecoinCheckout` with the starter's `ChainRegistry` and `PaymentLedger`:

```java
StablecoinCheckout checkout = new StablecoinCheckout(
        chains, ledger, recipient, new BigDecimal("1.00"))
        .setTokens("USDC", "PYUSD")
        .setPreferredChain(11155111);
```

Plain Anvil chain 31337 does not have built-in token entries, so it cannot be used for this checkout. To exercise checkout against a local Sepolia fork, set `SEPOLIA_RPC_URL` and run Anvil with the Sepolia chain ID:

```bash
anvil --fork-url "$SEPOLIA_RPC_URL" --chain-id 11155111
```

Start the demo with its Development wallet disabled and the Sepolia RPC redirected to the fork:

```bash
mvn spring-boot:run -pl web3-vaadin-demo \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--web3.dev.mock-wallet.enabled=false --web3.chains.11155111.rpc-url=http://127.0.0.1:8545"
```

Configure a browser wallet network to use chain ID 11155111 and `http://127.0.0.1:8545`, then connect an account that holds Sepolia test tokens. The fork executes transactions locally against the Sepolia token contract state; it does not send them to Sepolia. Checkout success is shown only after the normal receipt and transfer verification.

## 6. Send transactions and batch calls

With Anvil running and the `demo` profile active, open
<http://localhost:8080/transactions>. The route is anonymous. Connect the
Development wallet, whose chain is 31337. The page shows the expected network,
native balance, single transaction status and an EIP-5792 batch form. Plain
Anvil chain 31337 has no built-in USDC entry, so the page correctly omits a
USDC balance there.

For a single transfer, enter another Anvil account address and an ETH amount.
The status component follows the submitted hash until one confirmation by
default; choose two confirmations if you mine an additional block. For a batch,
enter two destinations and amounts, keep `atomicRequired` false, choose the
fallback policy, and submit. The Development wallet reports atomic batching as
unsupported but accepts non-atomic batches, executes calls sequentially, and
returns status plus receipts. This does not claim atomic execution. The
Development wallet stores batch status in memory, so a server restart discards
it.

To observe endpoint failover, start a second Anvil node on port 8546 in another
terminal. Add these properties to the demo profile (or pass equivalent
`--web3.chains.31337.rpc-urls[0]` and `[1]` arguments):

```properties
web3.chains.31337.rpc-urls[0]=http://127.0.0.1:8545
web3.chains.31337.rpc-urls[1]=http://127.0.0.1:8546
```

The configured `rpc-urls` list takes precedence over the existing single
`rpc-url`. Stop the primary Anvil process and make a request that reads chain
state; after transport failure, the client uses the second endpoint. Restart
the primary and a later request probes for recovery. The demo includes
Spring Boot Actuator and exposes only `/actuator/health` under this profile:

```bash
curl -i http://localhost:8080/actuator/health
```

Overall health is `DOWN` if any configured chain has all monitored endpoints
open, `UP` when no chain meets that condition, and `UNKNOWN` when there are no
configured chains. The demo keeps
`management.endpoint.health.show-details=never`, so endpoint details are not
exposed by default. For a trusted local diagnostic session only, details can
be enabled with `management.endpoint.health.show-details=always`; do not use
that setting on an internet-facing demo. A one-endpoint chain is reported as
`UNMONITORED` in details because no circuit-breaker snapshot exists.

## 7. Test SIWE without a browser wallet

Add the test kit with test scope:

```xml
<dependency>
    <groupId>com.wontlost</groupId>
    <artifactId>web3-vaadin-test</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

Use `SiweTestSupport` to create a canonical SIWE message and signature. Pass the fixture to the real verifier:

```java
TestNonceStore nonces = new TestNonceStore();
SiweTestSupport fixture = new SiweTestSupport(
        "localhost", "http://localhost:8080", 11155111, nonces);
SiweTestSupport.SignedMessage signed = fixture.valid();
VerifiedSignIn verified = new SiweVerifier(nonces, Clock.systemUTC()).verify(
        signed.message(), signed.signature(),
        new SiweExpectations("localhost", "http://localhost:8080", Set.of(11155111L), Duration.ofMinutes(5)));
```

Pass the signature and message through the application's `SiweVerifier` to exercise real verification. `TestWallet.anvil(0)` and `TestWallet.anvil(1)` expose public Anvil keys for local tests only. `web3-vaadin-test` is test infrastructure; it does not bypass signature verification or confirm checkout payments.

## Troubleshooting

- **No wallet appears:** install a browser wallet extension or activate the `demo` profile with Anvil running.
- **Development wallet is missing:** check `web3.dev.mock-wallet.enabled=true`, the profile name and that Vaadin is not in production mode.
- **Application refuses to start:** the development wallet cannot run in Vaadin production mode; disable the profile or the mock-wallet setting.
- **SIWE verification fails:** check the browser origin against `web3.siwe.domain` and `web3.siwe.uri`, the allowed chain IDs, nonce lifetime and message timestamps.
- **Protected route still redirects:** ensure `SecurityFilterChain` uses `VaadinSecurityConfigurer.vaadin()` and construct login with both the injected `NonceStore` and `Web3SiweLoginConfigurer`.
- **Login succeeds but Spring does not retain authentication:** use the servlet-compatible Vaadin push transport; pure WebSocket callbacks cannot persist a session cookie.
- **Sepolia RPC or checkout fails:** verify RPC reachability, selected chain, token contract, test token balance and recipient address. Plain Anvil has no Sepolia assets; use the fork command above.
- **Anvil chain ID mismatch:** start Anvil with chain ID 31337 and keep `web3.dev.mock-wallet.chain-id=31337`.
