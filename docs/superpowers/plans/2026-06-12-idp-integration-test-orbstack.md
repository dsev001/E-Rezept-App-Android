# Run IDP Integration Test Against orb.local (OrbStack CA + fd Trust Anchor) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `IdpIntegrationTest` (real virtual-health-card auth flow) runnable against the local TU environment (`*.orb.local`) by trusting the OrbStack CA in unit-test JVMs, and refresh the stale `APP_TRUST_ANCHOR_BASE64_TEST` from the regenerated fd test PKI so `TruststoreIntegrationTest` can work too.

**Architecture:** Three independent pieces. (1) A local PKCS12 truststore (`~/.erp-android/local-test-truststore.p12` = JDK cacerts + OrbStack Development Root CA), injected into all forked unit-test JVMs via `javax.net.ssl.trustStore` system properties — wired in the two base Gradle convention scripts, gated on a `LOCAL_TEST_TRUSTSTORE` key in gitignored `ci-overrides.properties` (no-op for everyone else; OkHttp uses the default `SSLContext`, so no test-code changes). (2) The hardcoded `TEST_RUN_WITH_IDP_INTEGRATION` / `TEST_RUN_WITH_TRUSTSTORE_INTEGRATION` BuildKonfig literals become `overrides()`-backed so a `-P` Gradle property flips them per invocation. (3) `APP_TRUST_ANCHOR_BASE64_TEST` in both `ci-overrides.properties` copies is refreshed from `fd/credentials/ca/rca.crt`.

**Tech Stack:** Gradle precompiled script plugins (Kotlin DSL), buildkonfig, `keytool`/`openssl`/`security` CLI, JUnit4 (`Assume`-gated integration tests, already exist — no new test code).

**Verified background facts (researched 2026-06-12):**
- `https://idp-server.ref-idp-server.orb.local/.well-known/openid-configuration` answers HTTP 200; its TLS cert is issued by `O=OrbStack Development, CN=OrbStack Development Root CA` (OrbStack terminates TLS for `*.orb.local`). That CA is in the macOS keychain (exportable via `security find-certificate -p -c "OrbStack Development Root CA"`), but NOT in any JDK `cacerts` → JVM HTTPS fails with PKIX.
- `https://fachdienst.fachdienst.orb.local` did NOT respond during research — the fachdienst container may be down. Task 6 probes before running.
- `fd/credentials/ca/rca.crt` = `CN=GEM.RCA99 TEST-ONLY`, notBefore **Jun 12 00:21:00 2026** (PKI regenerated). The `APP_TRUST_ANCHOR_BASE64_TEST` in `ci-overrides.properties` is the **Jun 8** predecessor → STALE (verified by byte comparison).
- Unit tests fork from the Gradle daemon JVM (sdkman Temurin; no toolchain/javaLauncher override in the build). `-Djavax.net.ssl.trustStore` on the `Test` task reaches OkHttp because OkHttp defaults to the JVM default SSLContext.
- `IdpIntegrationTest` (`core/src/test/kotlin/de/gematik/ti/erp/app/idp/usecase/IdpIntegrationTest.kt`) is gated by `Assume.assumeTrue(BuildKonfig.TEST_RUN_WITH_IDP_INTEGRATION)` and targets `BuildKonfig.IDP_SERVICE_URI`; with `buildkonfig.flavor=googleTuInternal` (gradle.properties) + the user's ci-overrides, that is the local ref-idp. Its `truststoreUseCase.checkIdpCertificate` is mocked — the trust anchor does NOT affect it.
- `TruststoreIntegrationTest` (`app/android/src/test/java/de/gematik/ti/erp/app/vau/TruststoreIntegrationTest.kt`) is gated by `TEST_RUN_WITH_TRUSTSTORE_INTEGRATION`, targets `BuildKonfig.BASE_SERVICE_URI` (local fachdienst), sends `X-Api-Key: BuildKonfig.ERP_API_KEY` (= `ERP_API_KEY_GOOGLE_TU` from ci-overrides — present), and validates with `BuildKonfig.APP_TRUST_ANCHOR_BASE64` (= `APP_TRUST_ANCHOR_BASE64_TEST` for TU) → needs the refreshed anchor AND fresh OCSP responses from the fd PKI (`VAU_OCSP_RESPONSE_MAX_AGE=12` hours).
- The flags at `common/build.gradle.kts:285-287` are currently literals inside the per-flavor `defaultConfigs(flavor)` function:
  ```kotlin
            // test configs
            buildConfigField(BOOLEAN, "TEST_RUN_WITH_TRUSTSTORE_INTEGRATION", "false")
            buildConfigField(BOOLEAN, "TEST_RUN_WITH_IDP_INTEGRATION", "false")
  ```
- The `overrides()` delegate (`buildSrc/.../DependenciesPlugin.kt`) resolves: `ci-overrides.properties` → Gradle project properties (`-P`) → `gradle.properties` → `""`. So `-PTEST_RUN_WITH_IDP_INTEGRATION=true` works as long as the key is NOT in ci-overrides (file wins over `-P`). Do NOT add the flags to ci-overrides.properties permanently.
- Execution happens in the worktree `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/.claude/worktrees/quizzical-knuth-a6c27d` (has its own copies of gitignored `ci-overrides.properties` + `local.properties`). Changes to ci-overrides must be mirrored to the main checkout `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties` so normal dev builds benefit.

---

### Task 1: Build the local test truststore (no repo changes)

Creates `~/.erp-android/local-test-truststore.p12` = full JDK cacerts (so public-CA HTTPS in other tests keeps working) + OrbStack Development Root CA. PKCS12 is JDK-version independent.

**Files:**
- Create (local, outside repo): `/Users/dsev001/.erp-android/local-test-truststore.p12`

- [ ] **Step 1: Export the OrbStack CA from the macOS keychain**

```bash
mkdir -p ~/.erp-android
security find-certificate -p -c "OrbStack Development Root CA" > /tmp/orbstack-ca.pem
openssl x509 -in /tmp/orbstack-ca.pem -noout -subject -enddate
```
Expected output contains: `subject=O=OrbStack Development, OU=Containers & Services, CN=OrbStack Development Root CA` and `notAfter=May 27 01:16:59 2036 GMT`.

- [ ] **Step 2: Create the truststore (cacerts copy + import)**

```bash
JDK_HOME=$(dirname "$(dirname "$(readlink -f "$(which java)")")")
rm -f ~/.erp-android/local-test-truststore.p12
keytool -importkeystore \
  -srckeystore "$JDK_HOME/lib/security/cacerts" -srcstorepass changeit \
  -destkeystore ~/.erp-android/local-test-truststore.p12 -deststoretype pkcs12 -deststorepass changeit \
  -noprompt 2>/dev/null
keytool -importcert -noprompt -alias orbstack-dev-root \
  -file /tmp/orbstack-ca.pem \
  -keystore ~/.erp-android/local-test-truststore.p12 -storepass changeit
```
Expected: last command prints `Certificate was added to keystore`.

- [ ] **Step 3: Verify the CA is in the truststore**

```bash
keytool -list -keystore ~/.erp-android/local-test-truststore.p12 -storepass changeit 2>/dev/null | grep -i orbstack
```
Expected: `orbstack-dev-root, <date>, trustedCertEntry,`

---

### Task 2: Wire the integration-test flags through `overrides()`

**Files:**
- Modify: `common/build.gradle.kts` (declarations near line 119; literals at lines 285-287)

- [ ] **Step 1: Add the property declarations**

In `common/build.gradle.kts`, the `// debug` section currently reads:
```kotlin
// debug
val DEBUG_TEST_IDS_ENABLED: String by overrides()
val DEBUG_VISUAL_TEST_TAGS: String? by project
val BUILD_TYPE_MINIFIED_DEBUG: String by overrides()
```
Directly below it add:
```kotlin
// integration tests against the configured environment
// (enable per run with -PTEST_RUN_WITH_IDP_INTEGRATION=true / -PTEST_RUN_WITH_TRUSTSTORE_INTEGRATION=true)
val TEST_RUN_WITH_IDP_INTEGRATION: String by overrides()
val TEST_RUN_WITH_TRUSTSTORE_INTEGRATION: String by overrides()
```

- [ ] **Step 2: Replace the hardcoded literals**

In the same file, replace:
```kotlin
            // test configs
            buildConfigField(BOOLEAN, "TEST_RUN_WITH_TRUSTSTORE_INTEGRATION", "false")
            buildConfigField(BOOLEAN, "TEST_RUN_WITH_IDP_INTEGRATION", "false")
```
with:
```kotlin
            // test configs
            buildConfigField(BOOLEAN, "TEST_RUN_WITH_TRUSTSTORE_INTEGRATION", TEST_RUN_WITH_TRUSTSTORE_INTEGRATION.ifEmpty { "false" })
            buildConfigField(BOOLEAN, "TEST_RUN_WITH_IDP_INTEGRATION", TEST_RUN_WITH_IDP_INTEGRATION.ifEmpty { "false" })
```
(The `.ifEmpty { "false" }` is required: `overrides()` yields `""` when a key is set nowhere, and buildkonfig needs a valid boolean literal.)

- [ ] **Step 3: Verify the default stays OFF (regression check)**

```bash
./gradlew :core:testDebugUnitTest --tests "de.gematik.ti.erp.app.idp.usecase.IdpIntegrationTest"
```
Expected: BUILD SUCCESSFUL; the `IdpIntegrationTest` tests are reported as **skipped** (JUnit assumption failure), NOT executed. Check: `grep -o 'tests="[0-9]*" skipped="[0-9]*"' core/build/test-results/testDebugUnitTest/TEST-de.gematik.ti.erp.app.idp.usecase.IdpIntegrationTest.xml` → `tests="3" skipped="3"`.

- [ ] **Step 4: Commit**

```bash
git add common/build.gradle.kts
git commit -m "Allow enabling IDP and truststore integration tests via Gradle properties

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Red phase — prove TLS is the only blocker

No code changes. Requires the ref-idp container running (probe first).

- [ ] **Step 1: Probe the local IDP**

```bash
curl -s --max-time 6 -o /dev/null -w "%{http_code}\n" https://idp-server.ref-idp-server.orb.local/.well-known/openid-configuration
```
Expected: `200`. If connection fails: the ref-idp-server container is down — STOP and report; the user must start it (OrbStack).

- [ ] **Step 2: Run the integration test WITHOUT the truststore wiring**

```bash
./gradlew :core:testDebugUnitTest --tests "de.gematik.ti.erp.app.idp.usecase.IdpIntegrationTest" -PTEST_RUN_WITH_IDP_INTEGRATION=true
```
Expected: BUILD FAILED. All 3 tests run (not skipped) and fail with `javax.net.ssl.SSLHandshakeException: PKIX path building failed ... unable to find valid certification path to requested target` (visible in `core/build/reports/tests/testDebugUnitTest/` or the XML under `core/build/test-results/testDebugUnitTest/`).
This is the expected "red": the flag wiring works, the endpoint is reachable, and the JVM truststore is the only missing piece. Any OTHER failure type (4xx/5xx HTTP, connection refused, crypto errors) means a different problem — STOP and report it instead of proceeding.

---

### Task 4: Inject the truststore into unit-test JVMs (green phase)

**Files:**
- Modify: `scripts/src/main/kotlin/base-android-library.gradle.kts` (append at end of file, after the `secrets {}` block)
- Modify: `scripts/src/main/kotlin/base-android-application.gradle.kts` (append at end of file, after the `secrets {}` block)
- Modify (gitignored, both copies): `ci-overrides.properties` in the worktree AND `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties`

- [ ] **Step 1: Append the wiring to BOTH base scripts**

Append this exact block to the END of `scripts/src/main/kotlin/base-android-library.gradle.kts` AND `scripts/src/main/kotlin/base-android-application.gradle.kts` (same content twice — the two convention scripts intentionally mirror each other, see their duplicated `secrets {}` blocks):

```kotlin

// Trust local development CAs (e.g. OrbStack) in unit-test JVMs.
// No-op unless LOCAL_TEST_TRUSTSTORE is set in ci-overrides.properties or passed as a Gradle property.
val ciOverrideProperties = java.util.Properties().apply {
    val file = project.rootProject.file("ci-overrides.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val localTestTrustStore = ciOverrideProperties.getProperty("LOCAL_TEST_TRUSTSTORE")
    ?: project.findProperty("LOCAL_TEST_TRUSTSTORE") as? String
if (localTestTrustStore != null) {
    val localTestTrustStorePassword = ciOverrideProperties.getProperty("LOCAL_TEST_TRUSTSTORE_PASSWORD")
        ?: project.findProperty("LOCAL_TEST_TRUSTSTORE_PASSWORD") as? String
        ?: "changeit"
    tasks.withType<Test>().configureEach {
        systemProperty("javax.net.ssl.trustStore", localTestTrustStore)
        systemProperty("javax.net.ssl.trustStorePassword", localTestTrustStorePassword)
    }
}
```
(`Test` and `withType` need no imports — the Gradle API and `org.gradle.kotlin.dsl.*` are implicitly imported in precompiled `.gradle.kts` scripts.)

- [ ] **Step 2: Add the truststore path to BOTH ci-overrides.properties copies**

Append to `ci-overrides.properties` in the worktree root AND to `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties` (absolute path — `~` does not expand in properties files):
```properties
LOCAL_TEST_TRUSTSTORE=/Users/dsev001/.erp-android/local-test-truststore.p12
```

- [ ] **Step 3: Green run**

```bash
./gradlew :core:testDebugUnitTest --tests "de.gematik.ti.erp.app.idp.usecase.IdpIntegrationTest" -PTEST_RUN_WITH_IDP_INTEGRATION=true
```
Expected — primary success criterion: **`authenticate with health card` PASSES** (full real flow: discovery doc → challenge → brainpoolP256r1 signature with the virtual card key → token exchange → non-empty access token).
The two pairing tests (`authenticate with health card and get paired devices`, `authenticate with key store and get paired devices`) depend on the ref-idp's pairing endpoint configuration. If they fail with HTTP-level errors (e.g. 4xx on `pairing` endpoints) while the primary test passes, record the exact errors and report them as environment findings — do NOT chase them with code changes.
If the primary test still fails with PKIX: the test JVM didn't get the properties — verify with `./gradlew :core:testDebugUnitTest --tests "...IdpIntegrationTest" -PTEST_RUN_WITH_IDP_INTEGRATION=true --info | grep trustStore` and check Step 1/2 for typos.

- [ ] **Step 4: Regression check — default run still green/skipped**

```bash
./gradlew :core:testDebugUnitTest --tests "de.gematik.ti.erp.app.idp.usecase.IdpIntegrationTest"
```
Expected: BUILD SUCCESSFUL, 3 skipped (the truststore properties are now always injected on this machine, which is harmless — the store is a superset of cacerts).

- [ ] **Step 5: Commit**

```bash
git add scripts/src/main/kotlin/base-android-library.gradle.kts scripts/src/main/kotlin/base-android-application.gradle.kts
git commit -m "Trust local development CA in unit-test JVMs via LOCAL_TEST_TRUSTSTORE

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Refresh APP_TRUST_ANCHOR_BASE64_TEST from the regenerated fd PKI

The fd test PKI was regenerated on Jun 12; the anchor in ci-overrides is the Jun 8 one (verified stale). This affects `TruststoreIntegrationTest` AND the app's own VAU/truststore validation on TU — refreshing is correct regardless of Task 6.

**Files:**
- Modify (gitignored, both copies): worktree `ci-overrides.properties` AND `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties`

- [ ] **Step 1: Replace the anchor in both files**

```bash
NEW_ANCHOR=$(openssl x509 -in /Users/dsev001/projects/dsev001/fd/credentials/ca/rca.crt -outform DER | base64 | tr -d '\n')
sed -i '' "s|^APP_TRUST_ANCHOR_BASE64_TEST=.*|APP_TRUST_ANCHOR_BASE64_TEST=$NEW_ANCHOR|" ci-overrides.properties
sed -i '' "s|^APP_TRUST_ANCHOR_BASE64_TEST=.*|APP_TRUST_ANCHOR_BASE64_TEST=$NEW_ANCHOR|" /Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties
```
(base64 alphabet contains no `&` or `|`, so the sed replacement is safe.)

- [ ] **Step 2: Verify both copies decode to the Jun 12 cert**

```bash
grep "^APP_TRUST_ANCHOR_BASE64_TEST=" ci-overrides.properties | cut -d= -f2- | base64 -d | openssl x509 -inform DER -noout -subject -startdate
grep "^APP_TRUST_ANCHOR_BASE64_TEST=" /Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties | cut -d= -f2- | base64 -d | openssl x509 -inform DER -noout -subject -startdate
```
Expected (both): `subject=C=DE, O=gematik TEST-ONLY - NOT-VALID, CN=GEM.RCA99 TEST-ONLY` and `notBefore=Jun 12 00:21:00 2026 GMT`.

Nothing to commit (gitignored files only).

---

### Task 6: TruststoreIntegrationTest against the local fachdienst (conditional)

Only runs if the fachdienst container is up — it was NOT responding during research.

- [ ] **Step 1: Probe the fachdienst**

```bash
curl -s --max-time 6 -o /dev/null -w "%{http_code}\n" https://fachdienst.fachdienst.orb.local/CertList
```
Expected: an HTTP status (`200`). If the connection fails entirely: report "fachdienst container is down — Task 6 skipped; run it later with the command below" and finish the plan. Do NOT try to start the fachdienst yourself.

- [ ] **Step 2: Run the truststore integration test**

```bash
./gradlew :app:android:testGoogleTuInternalDebugUnitTest --tests "de.gematik.ti.erp.app.vau.TruststoreIntegrationTest" -PTEST_RUN_WITH_TRUSTSTORE_INTEGRATION=true
```
Expected on success: PASS, with `Truststore established - received public key: ...` in the test stdout (visible in the HTML report under `app/android/build/reports/tests/`).
Known environment-dependent failure modes (report, don't code around):
- `PKIX path building failed` → truststore wiring problem (re-check Task 4 — note this module uses `base-android-application.gradle.kts`).
- Trust-anchor/cert-chain validation errors from `TrustedTruststore.create` → fd PKI vs anchor mismatch (re-check Task 5, or the fd's `update-trustlists.sh` needs a run).
- OCSP-age errors → stale OCSP responses; the fd side has `run-ocsp.sh` (OCSP responses may be at most `VAU_OCSP_RESPONSE_MAX_AGE=12` hours old).

---

### Final state

- Tracked commits: Task 2 (`common/build.gradle.kts`) and Task 4 (two base scripts) — both generic, no machine-specific data, default behavior unchanged for everyone else.
- Machine-local: truststore file, two ci-overrides entries (`LOCAL_TEST_TRUSTSTORE`, refreshed `APP_TRUST_ANCHOR_BASE64_TEST`) in both checkout copies.
- From now on the virtual-health-card auth flow is verifiable any time with:
  ```bash
  ./gradlew :core:testDebugUnitTest --tests "de.gematik.ti.erp.app.idp.usecase.IdpIntegrationTest" -PTEST_RUN_WITH_IDP_INTEGRATION=true
  ```
