# Implementation log

## Solana Spring Boot auto-configuration

- Reused the starter's optional dependency and `@ConditionalOnClass` patterns; optional wallet types are isolated in nested configuration classes.
- Added `Web3SolanaProperties` with cluster-key validation and the existing `web3.rpc.*` failover/timeout settings.
- A singleton `Clock.systemUTC()` bean is injected into both `InMemorySiwsChallengeStore` and `SiwsVerifier` to keep challenge expiry checks on one time base.
- `SolanaClientLifecycle` owns configured clients and closes every client on context shutdown, continuing after an individual close failure.
- `SolanaClusters` is placed in `VaadinContext` even when empty so consumers such as the SPL token gate can consistently find the attribute.
- The dev-wallet integration logs only its public address and cluster and explicitly refuses production mode before registration.
- Tooling note: sequential-thinking, code-index, and Shrimp task manager were used. The requested execution Codex MCP was not available in this session; implementation therefore followed the recorded plan in the current agent. An initial Shrimp `process_thought` call used the wrong argument schema and was retried with the documented fields.
- Verification status: pending the three requested offline Maven commands.

### Verification results

- `mvn -B -ntp -o install -DskipTests -pl web3-vaadin-server,web3-vaadin-solana` — PASS.
- `mvn -B -ntp -o test -pl web3-vaadin-spring-boot-starter` — PASS; 73 tests passed in the main execution and 3 passed with optional modules removed. The 12 Solana context tests cover empty defaults, one endpoint, Solana failover ordering/dialect, unknown clusters, commitment/timeout, deterministic and invalid seeds, optional class filtering, signature isolation, bean overrides, shared Clock, client shutdown, and production wallet refusal.
- `mvn -B -ntp -o -q compile -pl web3-vaadin-demo` — PASS (exit code 0).
- `git diff --check` — PASS.
- During development, the first targeted compile ran before the optional dependency was added and failed on the missing wallet test package; adding the optional dependency resolved it. A targeted `-Dtest` run also selected tests during the dedicated optional-module execution; the prescribed full starter command, which does not set `-Dtest`, passes both executions.

## Review fixes (2026-10-09)

- Removed the context-wide `Clock` bean. SIWS store and verifier now independently request the optional user Clock and otherwise fall back to `Clock.systemUTC()`; tests confirm no starter Clock bean, default challenge issuance/storage, and same-instance custom Clock injection.
- Set the demo view cluster explicitly to `devnet` in the default properties and `localnet` in the `demo` profile, keeping the local dev wallet and view on the same cluster.
- `mvn -B -ntp -o test -pl web3-vaadin-spring-boot-starter` — PASS (73 tests plus 3 stripped-optional-module tests).
- `mvn -B -ntp -o -q compile -pl web3-vaadin-demo` — PASS (exit code 0).

## Solana background RPC executor (2026-10-09)

- Added `SolanaBackgroundExecutor` in `web3-vaadin-server` so the starter can register a typed Vaadin-context attribute even when optional Solana UI classes are absent. The wrapper leaves application-provided executors alone; only the starter-created managed bounded pool is closed.
- Solana transaction status reads and signed-transaction fallback sends now resolve an explicit executor, then the Vaadin service/session context, then the common pool. Their executor fields are transient. Rejected status work clears its in-flight flag for retry; rejected sends return the specified server-busy wallet error.
- Added the starter's bounded 8-thread/256-queue daemon pool defaults, positive-value binding checks, context registration, and graceful shutdown (3-second wait, then forced shutdown). The test checks ownership semantics and registration.
- Updated README guidance for browser work and Spring Boot properties, and added a changelog entry.
- Verification:
  - `mvn -B -ntp -o install -DskipTests -pl web3-vaadin-server,web3-vaadin-solana` — PASS.
  - `mvn -B -ntp -o test -pl web3-vaadin-solana,web3-vaadin-spring-boot-starter` — PASS; 51 Solana tests, 77 starter tests, and 3 stripped-optional-module tests.
  - `(cd web3-vaadin-solana && npx vitest run)` — PASS; 1 test file, 32 tests.
  - `mvn -B -ntp -o -q compile -pl web3-vaadin-demo` — PASS.
  - `git diff --check` — PASS.
- The first starter/Solana test attempt caught unsupported `hasRootCauseMessage` chaining on this Spring `ApplicationContextAssert`; changed the assertions to inspect `getStartupFailure()` and reran the full command successfully.
- No credentials files were accessed or changed. No commit was created.

## Solana polling scheduling regression (2026-10-09)

- Confirmed the reviewed failure: `poll()` set `checking` before delegating, so the guard in `scheduleRead()` returned without submitting the RPC read. The regression test first ran against that buggy implementation and failed at the first poll assertion (`expected: <1> but was: <0>`).
- Made `scheduleRead()` the sole owner of the in-flight flag and its guard. `poll()` retains request-generation capture and request inputs, but no longer pre-sets or checks `checking`.
- Added a polling-path test with an attached `UI` and a recording executor: first poll submits exactly one task; a second poll while it is pending submits none.
- Extracted the wallet-response `thenCompose` continuation into a package-private helper used by `signAndSendTransaction`, and tested the signed-transaction fallback through that continuation. The configured executor captured the send task; running it produced the expected signature and sent wire transaction.
- Verification:
  - Regression test before fix: expected one task, observed zero — FAIL as expected.
  - Targeted post-fix `mvn -B -ntp -o -pl web3-vaadin-solana -Dtest=SolanaTransactionStatusTest test` — PASS (14 tests).
  - `mvn -B -ntp -o test -pl web3-vaadin-solana,web3-vaadin-spring-boot-starter` — PASS (53 Solana tests, 77 starter tests, 3 stripped-optional-module tests).
  - `mvn -B -ntp -o -q install -DskipTests -pl web3-vaadin-server,web3-vaadin-solana` — PASS.

## 2026-10-13 x402 supported kinds + public facilitator live IT (Codex)

- Replaced the legacy top-level x402 version/string kinds model with typed per-kind version, scheme, network, extra, extensions, and a case-sensitive `supports` helper. The HTTP client validates kind fields, defaults missing kind versions to 1 and absent extensions/extra to empty values.
- Updated facilitator fakes and demo/starter call sites. Added a real-shape response fixture, malformed kind coverage, public facilitator live IT, README guidance, and an Unreleased changelog entry.
- Validation run locally with Maven offline:
  - `mvn -B -ntp -o -q -pl web3-vaadin-server,web3-vaadin-test,web3-vaadin-spring-boot-starter,web3-vaadin-demo -am test-compile` — passed.
  - `mvn -B -ntp -o -q -pl web3-vaadin-server test -Dtest='FacilitatorClientTest,DefaultX402PaymentServiceTest'` — passed; reports: FacilitatorClientTest 7 tests/0 skipped, DefaultX402PaymentServiceTest 20/0.
  - `mvn -B -ntp -o -q -pl web3-vaadin-spring-boot-starter test -Dtest=X402AutoConfigurationTest` — passed; 12 tests/0 skipped.
  - `mvn -B -ntp -o -q -DskipTests install` — passed, installing updated OSS artifacts for the sibling repository.
- No live facilitator test was run. PaymasterLiveIT was also not run. No credentials were read or displayed; no commit or push was made.
- Surefire target counts for requested OSS unit suites: 39 tests, 0 skipped, 0 failures, 0 errors.

### 2026-10-13 审查决策（Claude）
- 审查 x402 supported kinds 修复 + X402PublicFacilitatorLiveIT：通过，综合 91（.claude/review-report.md）。
- 突变检查：删除 network 校验 → supportedRejectsMalformedKind 失败；已用 cp+touch 恢复。
- 真实运行：用户配置的 facilitator URL 上 /supported 通过；settle 用例因无 payer key 跳过。

### 2026-10-13 flake 修复（Claude 构建，Codex 审查 96 通过）
- SolanaTransactionStatusTest 超时用例本地 5/6 失败：注入 LongSupplier 时钟，测试推进虚拟时间。
## 2026-10-13 demo polish round 1 — OSS (Codex)

- Theme decision: Vaadin 25.3 documents Aura tokens as `--aura-*` and Lumo as `--lumo-*`, and advises against mixing the two. The shared library Lit components consume Lumo tokens, so the OSS app remains on Lumo. `@Theme("demo")` loads the demo CSS, with `@StyleSheet(Lumo.STYLESHEET)` explicitly loading the existing Lumo JAR stylesheet first; no `theme.json` is needed. A trial `theme.json` with `parent: "lumo"` failed because Vite could not locate a local parent theme folder, so it was removed. Pro should use the same annotation pairing before copying `styles.css` verbatim.
- Context and implementation patterns checked: approved demo polish plan; OSS and Pro context scans; Flow view/form/status implementations in DemoView, TransactionsView, and SolanaView; component wrappers in NftGalleryView, LoginView, and PaywallView; existing `TransactionsViewTest` and all demo `setId(...)` anchors. Verified part names in the connect, wallet picker, address, and Solana component source files. No Web3 call, event listener, route, asserted string, or existing ID was intentionally changed.
- Tools: sequential-thinking completed after correcting its required numeric arguments. Code-index indexed the OSS repository and searched existing route/ID patterns. Exa was unavailable due to its free-tier rate limit; official Vaadin documentation verified Aura/Lumo property namespaces and Vaadin 25 CSS ordering. Shrimp task planning, analysis, reflection, and task decomposition were completed. Codex MCP is not present in this session; no separate cross-agent review was available.
- Files changed: `web3-vaadin-demo/src/main/frontend/themes/demo/styles.css`; `DemoApplication`, `MainLayout`, and all eleven requested route views; new `com.wontlost.web3.demo.ui` page, section, callout, and status components; root README, CHANGELOG, and this log.
- Verification: initial compile caught and corrected API assumptions (`Theme` package, nested `SideNavItem` grouping, SVG DOM construction, and section helper return types). Initial production packaging caught the unavailable local theme parent and was corrected as recorded above. Final command results follow.
  - `mvn -B -ntp -o -pl web3-vaadin-demo -am -DskipTests compile` — PASS.
  - `mvn -B -ntp -o -pl web3-vaadin-demo test` — PASS; Surefire: 5 reports, 9 tests, 0 failures, 0 errors, 1 skipped.
  - `mvn -B -ntp -o -pl web3-vaadin-demo -am -Pproduction -DskipTests package` — PASS after removing the unresolved local theme parent declaration.
  - Runtime command from `web3-vaadin-demo` with profile `demo` and port `8091` — PASS; log confirmed `Tomcat started on port 8091`, and the development frontend bundle built successfully.
  - `curl -s http://localhost:8091/ | head -c 400` — PASS; returned the Vaadin-generated HTML document. The built development CSS asset contains `--demo-brand`. The process was stopped with Ctrl-C; graceful shutdown completed and port 8091 was free afterward.
  - Final Surefire recount: 5 reports, 9 tests, 0 failures, 0 errors, 1 skipped.
  - `git diff --check` — PASS. All original `setId(...)` values in the eleven route views match `main`.
  - Independent read-only review (`/root/oss_demo_review`) — PASS, 92/100, good taste, no blocking findings. Browser viewport acceptance remains with the user as requested; mobile overflow was not measured in a browser.


## 2026-10-14 demo polish round 1 fixes + round 2 — Pro (Codex)

- OSS browser-acceptance fixes: restored Lumo body sizes (xs/s/m/l/xl = 0.75/0.875/1/1.125/1.375rem), applied 1.25 heading ratio to page/section headings only, added a separate page header gap, made navbar actions a right-aligned flex cluster, kept the brand at Lumo size s on mobile, hid the theme-toggle label below 600px while retaining its aria-label, and made status pills intrinsic-width and start-aligned.
- Replaced generated section descriptions in DemoView and TransactionsView with explicit sentences. PaywallView now injects x402DemoArticlePolicy by qualifier. X402DemoConfiguration still defines both policies, and the quote controller/filter selection remains unchanged.
- Pro demo: created feat/demo-polish from clean main at ec8997a; added AppShellConfigurator metadata using @Theme("demo") plus @StyleSheet(Lumo.STYLESHEET). No Aura AppShell annotation existed in the inspected Pro source, so this installs the required Lumo theme setup directly. Copied the corrected OSS theme and four UI classes into web3-pro-demo; the OSS/Pro styles.css SHA-256 values are identical (b9d7e74c25bf3864fd16f732fb45807bd95260610853ad4a6c0cac13b4993429). Keep these two CSS files byte-identical.
- Rebuilt DemoLayout with a drawer toggle, SVG brand, badge, right-aligned theme toggle, icon groups, and preserved the demo profile gate for the Demo group and EE Console. Wrapped the six requested demo routes with page/section/callout components and the four operational library routes in wide page wrappers. No library module or dependency was changed. Existing route IDs, asserted strings, and action wiring remain unchanged.
- ProDemoApplicationTest initially failed because its direct-child cast expected EeConsole to be the route's first child. Updated only the test traversal to reach EeConsole through the required DemoPage wrapper; route, tenant, role, and text assertions are unchanged. The requested full Pro demo test command then passed.
- OSS verification:
  - mvn -B -ntp -o -q -pl web3-vaadin-demo test — PASS; 5 suites, 9 tests, 1 skipped, 0 failures, 0 errors.
  - mvn -B -ntp -o -q -pl web3-vaadin-demo -am -Pproduction -DskipTests package — PASS.
  - Demo-profile startup on port 8091 — Tomcat started; /, /paywall?resource=article:hidden-chapter, and /paid-article each returned HTTP 200. Captured log at web3-vaadin-demo/target/round2-acceptance.log has no InternalServerError matches. Process stopped; port 8091 is free.
- Pro verification:
  - mvn -B -ntp -o -q -pl web3-pro-demo test — PASS; 1 suite, 5 tests, 0 skipped, 0 failures, 0 errors.
  - mvn -B -ntp -o -q -pl web3-pro-demo -am -Pproduction -DskipTests package — PASS.
  - Anvil at 8545 returned chain ID 0x7a69. Demo-profile startup on 8090 — Tomcat started and Vaadin development frontend bundle built; /pay and /invoices each returned HTTP 200. Process stopped; port 8090 is free.
- git diff --check — PASS in both repositories. No commit or push was made. Port 8080 was not used; ~/.web3-live.env was not read.
- Tool note: code-index indexing succeeded, but its source query returned zero hits for present Java sources; direct rg/file inspection was used to confirm the patterns. Codex MCP is not available in this session; independent cross-review is being requested from the existing reviewer agent.

- Independent review follow-up (90/100, PASS, no blocker): updated both OSS and Pro toggles so their visible label names the next mode and aria-pressed tracks the active dark state; mobile CSS hides only the visible label while retaining aria-label. Migrated Pro DevPay feedback and Sponsored transaction results to DemoStatus with pending/success/error variants, preserving current text and handlers.
- Final verification after these review follow-ups:
  - OSS demo tests and production package rerun — PASS; test reports remain 5 suites, 9 tests, 1 skipped, 0 failures, 0 errors.
  - Final demo-profile boot on 8091 — PASS; /, /paywall?resource=article:hidden-chapter, and /paid-article all returned HTTP 200; target/round2-final-acceptance.log contains no InternalServerError matches; process stopped and port released.
  - Pro demo tests and production package rerun — PASS; test report 1 suite, 5 tests, 0 skipped, 0 failures, 0 errors.
  - Final Pro demo-profile boot on 8090 — PASS; /pay and /invoices returned HTTP 200; process stopped and port released.
  - Rechecked CSS SHA-256 equality after label styling update; git diff --check clean in both repositories.

## 2026-10-14 demo polish round 3 (Codex)

- Synchronized the OSS `DemoPage` implementation with Pro, adding `DemoPage.wide()` for a headerless wide container; package declaration is the only difference.
- Expanded the shared wide layout to 1400px and enabled wrapping/max-width for horizontal layouts and grids. The OSS and Pro theme stylesheets are byte-identical.
- Pro invoice, payments, reconciliation, and EE Console routes use the headerless container; Pro console test locates `EeConsole` by type through route children.
- Verification results are recorded after the requested local checks complete.
- Final verification: `mvn -B -ntp -o -q -pl web3-vaadin-demo test` passed (5 suites, 9 tests, 1 skipped, 0 failures/errors); `git diff --check` passed.
- Cross-repo parity checks passed: `cmp` confirms the two `styles.css` files are byte-identical, and `diff` confirms the four `ui` source files differ only by package declaration.

### 2026-10-14 审查决策（Claude）
- demo-polish 三轮后复审 91 通过；浏览器验收证据见 review-report.md。

## 2026-10-14 deep-audit-fix OSS (Codex)

- W1: Buffered servlet response status, headers, cookies, content length, charset, sendError, and redirects until the protected resource succeeds. Added bounded-response and filter error-path tests to preserve the resource response and skip settlement on sendError.
- W2: Replaced unbounded JSON-RPC body reads with bounded stream reads and a 16 MiB default limit plus a configurable constructor overload. Added response-boundary coverage. No `web3.rpc.*` transport configuration exists, so this implementation uses the transport constructor rather than adding new Spring properties.
- W3: Ordered RPC error classification so deterministic reverts are recognized before rate-limit text in revert data; added false-positive and explicit rate-limit cases.
- W4: Logged unexpected asynchronous SIWE completion failures and restored the UI to a failed, retryable state. Added a throwing-listener regression test.
- W5: Kept Pay disabled while a submitted transaction is pending and restarted its poller after reattachment. Added detach/reattach coverage.
- W6: Expired READY, VERIFIED, and FAILED payment records are purged before in-memory capacity checks. Extended store expiration/capacity coverage. The store package contains only `InMemoryPaidResourceStore`; no JDBC counterpart exists to align.
- W7: Moved synchronous catalog loading outside the cache monitor and guarded publication by generation. Added a latch test proving a slow load does not block refresh inspection.
- W8: Required absolute HTTPS provider URLs and updated the English validation text. Added an HTTP rejection assertion.
- W9: Added a bounded Caffeine cache keyed by chain ID and normalized token address for token decimals. Added a regression asserting separate TokenGate instances share the cached lookup.
- I1: Replaced 32-bit chain ID truncation with long-keyed chain-name maps in NetworkIndicator and StablecoinCheckout. Added regression coverage using chain ID 4294967297.
- I2: Reused static Jackson ObjectMapper instances in ResourcePolicy and Eip3009TypedDataFactory; existing x402 codec tests verify requirements and typed-data output.
- I3: Added a 100,000-entry default capacity and configurable limit to InMemoryNonceStore while preserving existing constructors. Added full-capacity and expired-capacity reuse coverage.
- I4: Injected the monitor Clock into PaymentController and used it for create and transaction-submit timestamps. Monitor API tests passed with the existing Clock bean.
- I5: Retained wallet event registrations and removed them before installing listeners on a replacement wallet. Added a regression test that fires an event on the replaced wallet.
- I6: Made HttpFacilitatorClient AutoCloseable, closed only internally created HttpClient instances, wired the starter bean destruction method, and tested that an injected shared client remains open.

### Verification results

- `mvn -B -ntp -o -q -pl web3-vaadin -am test` — PASS.
- `mvn -B -ntp -o -q -pl web3-vaadin-server -am test` — PASS.
- `mvn -B -ntp -o -q -pl web3-vaadin-onramp -am test` — PASS.
- `mvn -B -ntp -o -q -pl web3-vaadin-monitor -am test` — PASS.
- `ANVIL_RPC=http://127.0.0.1:8547 mvn -B -ntp -o clean install` — PASS; Surefire total: 623 tests, 0 failures, 0 errors, 0 skipped.
- `JAVA_HOME=/Users/rpang/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.8/Contents/Home TZ=UTC mvn -B -ntp -o test` — PASS; Surefire total: 623 tests, 0 failures, 0 errors, 3 skipped.
- `git diff --check` — PASS.
- No planned findings were omitted. The store package has no JDBC implementation; the W6 alignment request therefore applied only to the in-memory store.

### round 2

- R1: Changed TokenGate decimals caching from process-global static state to a bounded per-instance cache; the regression now evaluates two navigations through the same gate and confirms one decimals eth_call.
- R2: BoundedHttpServletResponse.copyTo now excludes buffered Content-Length metadata and always sets the real response length from the buffered body. The test supplies length 99 for a two-byte body and verifies the copied length is 2.
- R3: Added SiweException.Reason.INTERNAL_ERROR. Unexpected pre-completion runtime failures finish as INTERNAL_ERROR; failures from SignedInEvent listeners after completion are logged without firing SignInFailedEvent. Tests cover the listener failure and a missing-current-session store failure.
- R4: UiPolling now tracks a last-run time per subscription and dispatches according to 90% of each interval, with an injectable package-private clock setter. Simulated 500 ms polls produce six short callbacks and two 3-second callbacks.
- R5: Split the four aggregated Fixed bullets into individual English entries.
- Targeted server regression tests: `mvn -B -ntp -o -q -pl web3-vaadin-server test -Dtest=TokenGateTest,BoundedHttpServletResponseTest,SiweSessionRotationTest,UiPollingTest` — PASS.
- `ANVIL_RPC=http://127.0.0.1:8547 mvn -B -ntp -o clean install` — PASS; all 10 reactor projects succeeded; Surefire: 625 tests, 0 failures, 0 errors, 0 skipped.
- `JAVA_HOME=/Users/rpang/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.8/Contents/Home TZ=UTC mvn -B -ntp -o test` — PASS; all 10 reactor projects succeeded; Surefire XML total: 625 tests, 0 failures, 0 errors, 3 skipped.
- `git diff --check` — PASS.
