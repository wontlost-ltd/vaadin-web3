# Hosted Payment Monitor

The monitor is an optional, non-custodial service. It watches configured EVM JSON-RPC endpoints for payment transactions after a merchant's checkout page closes. It never requests, stores, or controls private keys and never holds customer funds. A merchant receives signed webhooks when an intent reaches a terminal state.

## Run locally

Build and run the executable service from the repository root:

```bash
mvn -B -ntp -pl monitor -am package
MONITOR_ADMIN_TOKEN='replace-with-a-long-random-value' \
MONITOR_RPC_11155111='https://your-sepolia-rpc.example' \
java -jar monitor/target/vaadin-web3-monitor-1.0.0-SNAPSHOT.jar
```

By default, H2 stores data in `./data/monitor`; port 8080 is used. Only `/actuator/health` is exposed by Actuator. The database directory must persist across restarts.

For PostgreSQL, set `MONITOR_DATABASE_URL` (for example `jdbc:postgresql://db:5432/monitor`), `MONITOR_DATABASE_USERNAME` and `MONITOR_DATABASE_PASSWORD`. The JDBC driver is selected from the URL. Flyway applies the same versioned migrations at startup. The PostgreSQL driver and Flyway PostgreSQL support are packaged in the service jar.

## Configuration

| Property | Environment variable | Default | Purpose |
|---|---|---|---|
| `monitor.rpc.<chainId>` | `MONITOR_RPC_<chainId>` | unset | EVM JSON-RPC URL; only chains with an RPC are accepted |
| `monitor.admin-token` | `MONITOR_ADMIN_TOKEN` | empty | Required to enable admin operations; unset or blank means every admin request returns 403 |
| `monitor.poll-interval` | `MONITOR_POLL_INTERVAL` | `5s` | Delay between worker batches |
| `monitor.default-expiry` | `MONITOR_DEFAULT_EXPIRY` | `1h` | Default intent lifetime; all intent lifetimes are limited to seven days |
| `monitor.not-before-tolerance` | `MONITOR_NOT_BEFORE_TOLERANCE` | `2m` | Time subtracted from intent creation for block timestamp clock skew |
| `monitor.pending-grace` | `MONITOR_PENDING_GRACE` | `1h` | How long an unmined submitted transaction remains eligible after expiry |
| `monitor.webhooks.allow-insecure` | `MONITOR_WEBHOOK_ALLOW_INSECURE` | `false` | Allows HTTP webhook URLs when enabled |
| `monitor.webhooks.allow-private-targets` | `MONITOR_WEBHOOK_ALLOW_PRIVATE_TARGETS` | `false` | Allows private/reserved DNS targets when enabled; use only for isolated local testing |
| `spring.datasource.url` | `MONITOR_DATABASE_URL` | `jdbc:h2:file:./data/monitor;AUTO_SERVER=TRUE` | JDBC URL |
| `spring.datasource.username` | `MONITOR_DATABASE_USERNAME` | `sa` | Database user |
| `spring.datasource.password` | `MONITOR_DATABASE_PASSWORD` | empty | Database password |
| `server.port` | `PORT` | `8080` | HTTP port |

For a chain, set a Java property such as `monitor.rpc.11155111=https://...` or its environment binding `MONITOR_RPC_11155111`. Configure RPC URLs only on trusted deployment infrastructure.

## Create a merchant

The admin token and merchant credentials must be sent over TLS outside local development. Admin calls use `X-Admin-Token`:

```bash
curl -sS -X POST http://localhost:8080/admin/merchants \
  -H 'X-Admin-Token: replace-with-a-long-random-value' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Example Shop","webhookUrl":"https://shop.example/webhooks/payments"}'
```

The response contains `merchantId`, `apiKey` (`wm_...`) and `webhookSecret` (`whsec_...`). These credentials are returned once. The service stores only a SHA-256 hash of the API key. It stores the webhook secret in plaintext so it can sign deliveries; encrypt the database volume and tightly restrict database access. There is currently no secret rotation endpoint.

## API

All merchant endpoints require `Authorization: Bearer <apiKey>`. Authentication failures return status 401 with a JSON body such as `{"error":"Invalid API key"}`. Admin authentication failures return 403.

Create an intent (token may be a built-in symbol or a built-in contract address for that chain):

```bash
curl -i -X POST http://localhost:8080/v1/payments \
  -H 'Authorization: Bearer wm_...' -H 'Content-Type: application/json' \
  -d '{"orderId":"order-1842","chainId":11155111,"token":"USDC","recipient":"0xYourChecksummedAddress","amount":"12.50","payer":"0xOptionalChecksummedPayer","minConfirmations":2,"expiresInSeconds":3600}'
```

The first identical request returns 201 and creates an `AWAITING_TRANSACTION` intent. Repeating the same merchant/order and payment parameters returns 200 with the same intent. Different parameters for the same order return 409. Decimal values with more places than the token supports are rejected. EVM addresses accept all-lower/all-upper forms or valid EIP-55 checksums and are returned checksummed.

Submit a transaction hash once the wallet reports it:

```bash
curl -i -X POST http://localhost:8080/v1/payments/<paymentId>/transaction \
  -H 'Authorization: Bearer wm_...' -H 'Content-Type: application/json' \
  -d '{"txHash":"0x<64 hexadecimal characters>"}'
```

The same hash is idempotent. A different hash or a terminal intent returns 409. Query an intent or a merchant's order:

```bash
curl -sS http://localhost:8080/v1/payments/<paymentId> -H 'Authorization: Bearer wm_...'
curl -sS 'http://localhost:8080/v1/payments?orderId=order-1842' -H 'Authorization: Bearer wm_...'
```

Intent fields include `id`, `orderId`, `chainId`, `token` metadata, `recipient`, decimal `amount`, optional `payer`, `minConfirmations`, `status`, `txHash`, `paidAmount`, `confirmations`, `notBefore`, `expiresAt`, `createdAt` and `updatedAt`. One merchant cannot access another merchant's intent; such IDs return 404.

Statuses progress from `AWAITING_TRANSACTION` to `PENDING`, then `CONFIRMING` and `CONFIRMED`. `FAILED`, `UNDERPAID`, `NO_MATCHING_TRANSFER`, `ALREADY_CLAIMED`, `PREDATES_ORDER` and `EXPIRED` are terminal. An intent awaiting a hash expires at its expiry. A submitted transaction with no receipt is given the configured pending grace period; a confirming transaction is followed until it confirms or becomes a terminal verification result.

Usage is an operational summary for later billing decisions:

```bash
curl -sS 'http://localhost:8080/admin/merchants/<merchantId>/usage?month=2026-10' \
  -H 'X-Admin-Token: replace-with-a-long-random-value'
```

It returns `intentsCreated`, `paymentsConfirmed` and `confirmedVolume` grouped by `<chainId>:<symbol>`. **The service does not define or charge fees; the operator decides the charging model.**

## Webhooks

The monitor POSTs `{ "id": "<deliveryId>", "type": "payment.confirmed|payment.failed|payment.expired", "createdAt": "...", "data": <intent> }`. Headers include `Content-Type: application/json`, `X-Web3-Monitor-Event-Id` and `X-Web3-Monitor-Signature: t=<unix-seconds>,v1=<hex-HMAC-SHA256>`.

The signature is HMAC-SHA256 of `<timestamp>.<exact-request-body>` using the merchant webhook secret. Verify before processing and make the event ID idempotent in the receiving application:

```java
import com.wontlost.web3.monitor.WebhookSignatures;
import java.time.Clock;
import java.time.Duration;

boolean valid = WebhookSignatures.verify(webhookSecret, signatureHeader, exactBody,
        Duration.ofMinutes(5), Clock.systemUTC());
if (!valid) {
    throw new IllegalArgumentException("Invalid webhook signature");
}
```

A 2xx response acknowledges delivery. Other responses and transport failures retry after 1m, 5m, 15m, 1h, 6h and 24h; after the final retry the delivery is marked failed. Requests time out after 10 seconds and redirects are not followed.

## Vaadin client

Register the client once at application initialization. The application `VaadinContext` owns it; components retain only the hosted intent ID so merchant credentials are not serialized with a UI session:

```java
PaymentMonitorClient client = new PaymentMonitorClient(URI.create(monitorUrl), apiKey);
VaadinServiceInitListener listener = event ->
        PaymentMonitorClient.register(event.getSource().getContext(), client);
```

Enable monitoring on a checkout with `.setPaymentMonitor(true)`. Without a registered client, the checkout fires `PaymentFailedEvent` before it sends a wallet transaction. The page can close after the wallet returns a hash; the service keeps checking independently. See the [checkout example](../README.md#stablecoin-checkout).

## Deployment and operations

- Use PostgreSQL for production and keep the database on durable storage. H2 file mode is intended for local use or a single small deployment.
- Multiple monitor instances can share PostgreSQL. Leases carry ownership tokens, so a worker whose lease was taken over cannot write its stale result.
- Webhook delivery is at least once: events can be delivered more than once and are not guaranteed to arrive in order. Receivers should deduplicate with `X-Web3-Monitor-Event-Id`. Each payment intent produces at most one terminal event.
- Put the service behind TLS and do not expose the admin token through a browser or frontend bundle.
- Back up the database and test restoring it. It contains merchant records, intents, durable transaction claims and pending webhook deliveries.
- Rotate admin tokens by changing the operator configuration and restarting. Merchant API keys and webhook secrets currently have no rotation API; recreate a merchant and migrate its integration manually to rotate them.
- Use `confirmedVolume` as input to the operator's billing system; the monitor performs no billing.

## Security notes

The service is non-custodial and watches configured chains through RPC; it does not have a wallet or hold funds. Merchant API keys are stored only as SHA-256 hashes. Webhook secrets are stored in plaintext and should be protected with database encryption and restricted access. Webhook URL validation rejects HTTP by default and blocks every private or reserved target:

- loopback, any-local and multicast addresses;
- RFC 1918 private ranges and link-local addresses, including the cloud metadata address `169.254.169.254`;
- `0.0.0.0/8`, `100.64.0.0/10`, `192.0.0.0/24`, `198.18.0.0/15` and `240.0.0.0/4`;
- IPv6 unique-local addresses and NAT64 (`64:ff9b::/96`).

The URL is checked when it is saved. At delivery time the webhook HTTP client uses the same policy as its DNS resolver, so the addresses that are checked are exactly the addresses it connects to. A hostname cannot pass the check and then be re-resolved to an internal address (DNS rebinding). Redirects are disabled. Enable private targets only for isolated local tests.
