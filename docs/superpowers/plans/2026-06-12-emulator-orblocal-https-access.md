# Emulator → orb.local HTTPS Access (TU stack) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the E-Rezept app, running in the Android emulator, reach the local TU stack (`*.orb.local` FD + ref-idp) over real HTTPS — fixing the `UnknownHostException` — entirely within the internal-debug build, leaving release untouched.

**Architecture:** Three coordinated changes, all gated to internal-debug builds: (1) a DNS override in the existing `BlockingDns` maps `*.orb.local` hostnames to their OrbStack bridge IPs (read from a new `BuildKonfig.ORB_LOCAL_DNS_OVERRIDES` field sourced from gitignored `ci-overrides.properties`); (2) a debug-only `network_security_config.xml` trusts the OrbStack Development Root CA for `orb.local`; (3) the Certificate Transparency interceptor excludes `*.orb.local` (its `failOnError=true` otherwise rejects OrbStack's non-CT-logged dev cert). slirp routing from the emulator to the OrbStack bridge IPs (e.g. `192.168.138.15:443`) is already confirmed working, and OrbStack serves the correct per-host cert by SNI.

**Tech Stack:** Kotlin, OkHttp `Dns`, Appmattus `certificatetransparency-android` DSL, Android `network-security-config`, buildkonfig, JUnit4 (existing `app/features/src/test` patterns).

**Verified background facts (researched 2026-06-12 on this machine):**
- Emulator is `sdk_gphone16k_arm64`, build type `user` (Google Play image) → **no `adb root`**, so `/system/etc/hosts` edits and system-CA installs are out. Everything must live in the app build.
- `*.orb.local` does not resolve inside the emulator (`ping` → unknown host) but normal domains do — OrbStack's resolver is host-only and the emulator's slirp NAT never consults it. This is the root cause of the `UnknownHostException`.
- From the emulator, **TCP to `192.168.138.15:443` succeeds** (`nc` → open) — slirp routes guest TCP to OrbStack bridge IPs. ICMP from the emulator is faked by slirp (garbage RTT); ignore ping for reachability.
- Host DNS: `fachdienst.vibrant-kalam-3e8bca.orb.local` → `192.168.138.15`, `idp-server.ref-idp-server.orb.local` → `192.168.138.3` (distinct per-container IPs; **they can change when containers are recreated** — Task 1 re-derives them).
- `curl --resolve fachdienst.vibrant-kalam-3e8bca.orb.local:443:192.168.138.15` → 200, cert CN=`fachdienst.vibrant-kalam-3e8bca.orb.local`, issuer `OrbStack Development Root CA`. So connecting to the bridge IP with SNI=hostname works and gets the right cert.
- The worktree `ci-overrides.properties` already points the app at the orb.local hosts: `BASE_SERVICE_URI_TU=https://fachdienst.vibrant-kalam-3e8bca.orb.local/`, `IDP_SERVICE_URI_TU=https://idp-server.ref-idp-server.orb.local/.well-known/`. The DNS override host keys MUST match these hostnames exactly.
- `BlockingDns` (`app/features/src/main/kotlin/de/gematik/ti/erp/app/di/BlockingDns.kt`) is the custom `Dns` already wired onto the main OkHttp client (`ClientBuilderModule.kt:48,63`); it delegates to `Dns.SYSTEM`. It is the single DNS hook.
- CT is installed via `addCertificateTransparencyInterceptor()` (`ClientBuilderModule.kt:140`, `failOnError = true`, no host excludes) and reused by `NetworkModule.kt:196` — editing that one function covers both clients.
- Gate: `BuildConfigExtension.isInternalDebug` = `BuildKonfig.INTERNAL && BuildConfig.DEBUG` (`app/features/.../utils/extensions/BuildConfigExtension.kt:30`), already imported in `ClientBuilderModule.kt:30`.
- App manifest references `android:networkSecurityConfig="@xml/network_security_config"` (`app/android/src/main/AndroidManifest.xml:41`); `app/android/src/debug/` already exists (has `AndroidManifest.xml`), so a `src/debug/res/xml/network_security_config.xml` overrides the resource for debug builds only.
- OrbStack CA PEM is exportable: `security find-certificate -p -c "OrbStack Development Root CA"` (also currently at `/tmp/orbstack-ca.pem`).
- Execution dir: the worktree `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/.claude/worktrees/quizzical-knuth-a6c27d`. Run all Gradle in the FOREGROUND (timeout up to 600000 ms); never background it.

**Out of scope (note, not tasks):** The FD's orb.local name churns because it runs under a worktree-named compose project (`vibrant-kalam-3e8bca`). Stabilizing it with a fixed compose project name would stop the `BASE_SERVICE_URI_TU` + IP map from needing updates each restart — a separate change in the `fd` repo.

---

### Task 1: Add `ORB_LOCAL_DNS_OVERRIDES` BuildKonfig field + ci-overrides entries

**Files:**
- Modify: `common/build.gradle.kts` (declarations ~line 119; android targetConfig ~line 418)
- Modify (gitignored, both copies): worktree `ci-overrides.properties` AND `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties`

- [ ] **Step 1: Re-derive the current orb.local IPs**

The app's configured hosts come from `BASE_SERVICE_URI_TU` / `IDP_SERVICE_URI_TU` in the worktree ci-overrides. Resolve their current IPs:
```bash
grep -E "BASE_SERVICE_URI_TU|IDP_SERVICE_URI_TU" /Users/dsev001/projects/dsev001/E-Rezept-App-Android/.claude/worktrees/quizzical-knuth-a6c27d/ci-overrides.properties
for h in fachdienst.vibrant-kalam-3e8bca.orb.local idp-server.ref-idp-server.orb.local; do
  printf "%s=" "$h"; dscacheutil -q host -a name "$h" | awk '/^ip_address/{print $2; exit}'
done
```
Expected: two `host=ip` lines (e.g. `fachdienst.vibrant-kalam-3e8bca.orb.local=192.168.138.15`, `idp-server.ref-idp-server.orb.local=192.168.138.3`). If the FD hostname in the grep differs from what's resolved here, use the hostname from the grep (it must match `BASE_SERVICE_URI_TU`). Save these two `host=ip` pairs for Step 3.

- [ ] **Step 2: Declare the override property in `common/build.gradle.kts`**

After the integration-test flags block (added previously):
```kotlin
// integration tests against the configured environment
// (enable per run with -PTEST_RUN_WITH_IDP_INTEGRATION=true / -PTEST_RUN_WITH_TRUSTSTORE_INTEGRATION=true)
val TEST_RUN_WITH_IDP_INTEGRATION: String by overrides()
val TEST_RUN_WITH_TRUSTSTORE_INTEGRATION: String by overrides()
```
add:
```kotlin
// local DNS overrides for reaching *.orb.local from the emulator (internal-debug only)
// format: host1=ip1;host2=ip2  — sourced from gitignored ci-overrides.properties
val ORB_LOCAL_DNS_OVERRIDES: String by overrides()
```

- [ ] **Step 3: Emit the BuildKonfig field (android target)**

In the `targetConfigs { create("android") { ... } }` block, after `buildConfigField(BOOLEAN, "VAU_ENABLE_INTERCEPTOR", "true")` add:
```kotlin
            buildConfigField(STRING, "ORB_LOCAL_DNS_OVERRIDES", ORB_LOCAL_DNS_OVERRIDES)
```
(`overrides()` returns `""` when unset, so non-dev builds get an empty string → no overrides. Safe to emit for all android variants since it's only consumed under `isInternalDebug` in Task 2.)

- [ ] **Step 4: Add the override line to BOTH ci-overrides copies**

Using the two pairs from Step 1, append one line (pairs joined by `;`) to the worktree `ci-overrides.properties` AND `/Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties`:
```properties
ORB_LOCAL_DNS_OVERRIDES=fachdienst.vibrant-kalam-3e8bca.orb.local=192.168.138.15;idp-server.ref-idp-server.orb.local=192.168.138.3
```
(Replace the hosts/IPs with Step 1's actual values. The `=` and `;` inside the value are fine in a properties file — only the first `=` separates key from value. Verify gitignored: `git status --porcelain ci-overrides.properties` → empty.)

- [ ] **Step 5: Verify BuildKonfig generates the field**

```bash
cd /Users/dsev001/projects/dsev001/E-Rezept-App-Android/.claude/worktrees/quizzical-knuth-a6c27d
./gradlew :common:generateBuildKonfig -q
grep -rn "ORB_LOCAL_DNS_OVERRIDES" common/build/buildkonfig/ | head
```
Expected: a generated line `const val ORB_LOCAL_DNS_OVERRIDES: String = "fachdienst...=...;idp...=..."` in an android BuildKonfig source.

- [ ] **Step 6: Commit (build script only — ci-overrides is gitignored)**

```bash
git add common/build.gradle.kts
git commit -m "Add ORB_LOCAL_DNS_OVERRIDES BuildKonfig field for emulator orb.local access

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: DNS override in `BlockingDns` (TDD)

**Files:**
- Modify: `app/features/src/main/kotlin/de/gematik/ti/erp/app/di/BlockingDns.kt`
- Create: `app/features/src/test/kotlin/de/gematik/ti/erp/app/di/BlockingDnsTest.kt`
- Modify: `app/features/src/main/kotlin/de/gematik/ti/erp/app/di/ClientBuilderModule.kt` (DI wiring at line 48)

- [ ] **Step 1: Write the failing test**

Create `app/features/src/test/kotlin/de/gematik/ti/erp/app/di/BlockingDnsTest.kt`:
```kotlin
/*
 * Copyright (Change Date see Readme), gematik GmbH
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the
 * European Commission – subsequent versions of the EUPL (the "Licence").
 * You may not use this work except in compliance with the Licence.
 *
 * You find a copy of the Licence in the "Licence" file or at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * In case of changes by gematik GmbH find details in the "Readme" file.
 *
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes by gematik find details in the "Readme" file.
 */

package de.gematik.ti.erp.app.di

import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class BlockingDnsTest {

    @Test
    fun `parseHostOverrides parses host=ip pairs`() {
        val result = parseHostOverrides("a.orb.local=192.168.1.2;b.orb.local=192.168.1.3")
        assertEquals(mapOf("a.orb.local" to "192.168.1.2", "b.orb.local" to "192.168.1.3"), result)
    }

    @Test
    fun `parseHostOverrides returns empty map for blank input`() {
        assertEquals(emptyMap(), parseHostOverrides(""))
    }

    @Test
    fun `parseHostOverrides ignores malformed entries and trims`() {
        val result = parseHostOverrides(" a.orb.local = 192.168.1.2 ;garbage; =1.2.3.4;b=")
        assertEquals(mapOf("a.orb.local" to "192.168.1.2"), result)
    }

    @Test
    fun `lookup returns overridden ip without system resolution`() {
        val dns = BlockingDns(mapOf("fachdienst.test.orb.local" to "192.168.138.15"))
        val result = dns.lookup("fachdienst.test.orb.local")
        assertEquals(1, result.size)
        assertEquals("192.168.138.15", result.first().hostAddress)
    }

    @Test
    fun `lookup still blocks telemetry domains`() {
        val dns = BlockingDns(mapOf("x.orb.local" to "192.168.1.2"))
        assertFailsWith<UnknownHostException> {
            dns.lookup("app-measurement.firebaselogging.googleapis.com")
        }
    }

    @Test
    fun `lookup delegates non-overridden hosts to system`() {
        val dns = BlockingDns(mapOf("x.orb.local" to "192.168.1.2"))
        // localhost resolves via the system resolver without network access
        val result = dns.lookup("localhost")
        assertTrue(result.any { it.isLoopbackAddress })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew :app:features:testDebugUnitTest --tests "de.gematik.ti.erp.app.di.BlockingDnsTest"
```
Expected: compilation failure / FAIL — `parseHostOverrides` is unresolved and `BlockingDns` has no map constructor yet.

- [ ] **Step 3: Implement the override in `BlockingDns.kt`**

Replace the class body and add the parse helper. The full updated file content below (keep the existing license header and KDoc above the class; only the class and the new top-level function change):
```kotlin
internal class BlockingDns(
    private val hostOverrides: Map<String, String> = emptyMap()
) : Dns {
    private val blockedDomainSuffixes = listOf(
        ".firebaselogging.googleapis.com",
        ".firebaseremoteconfig.googleapis.com"
    )

    override fun lookup(hostname: String): List<InetAddress> {
        if (blockedDomainSuffixes.any { hostname.endsWith(it) }) {
            throw UnknownHostException("Blocked by custom DNS: $hostname")
        }
        hostOverrides[hostname]?.let { ip ->
            return listOf(InetAddress.getByName(ip))
        }
        return Dns.SYSTEM.lookup(hostname)
    }
}

/**
 * Parses a `host1=ip1;host2=ip2` specification (from BuildKonfig.ORB_LOCAL_DNS_OVERRIDES)
 * into a host→ip map. Blank/malformed entries are ignored.
 */
internal fun parseHostOverrides(spec: String): Map<String, String> =
    spec.split(";")
        .mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            val host = parts.getOrNull(0)?.trim().orEmpty()
            val ip = parts.getOrNull(1)?.trim().orEmpty()
            if (parts.size == 2 && host.isNotEmpty() && ip.isNotEmpty()) host to ip else null
        }
        .toMap()
```
(`InetAddress.getByName(ip)` on a literal IP string parses it without a DNS lookup. The existing imports `okhttp3.Dns`, `java.net.InetAddress`, `java.net.UnknownHostException` already cover this.)

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew :app:features:testDebugUnitTest --tests "de.gematik.ti.erp.app.di.BlockingDnsTest"
```
Expected: BUILD SUCCESSFUL, 6 tests pass.

- [ ] **Step 5: Wire the override map into the DI client (internal-debug only)**

In `app/features/src/main/kotlin/de/gematik/ti/erp/app/di/ClientBuilderModule.kt`, add the import after `import de.gematik.ti.erp.app.utils.extensions.BuildConfigExtension` (line 30):
```kotlin
import de.gematik.ti.erp.app.BuildKonfig
```
Then replace line 48:
```kotlin
private val blockingDns = BlockingDns()
```
with:
```kotlin
private val blockingDns = BlockingDns(
    if (BuildConfigExtension.isInternalDebug) {
        parseHostOverrides(BuildKonfig.ORB_LOCAL_DNS_OVERRIDES)
    } else {
        emptyMap()
    }
)
```

- [ ] **Step 6: Verify the module still compiles**

```bash
./gradlew :app:features:compileGoogleTuInternalDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/features/src/main/kotlin/de/gematik/ti/erp/app/di/BlockingDns.kt \
        app/features/src/test/kotlin/de/gematik/ti/erp/app/di/BlockingDnsTest.kt \
        app/features/src/main/kotlin/de/gematik/ti/erp/app/di/ClientBuilderModule.kt
git commit -m "Resolve *.orb.local to OrbStack bridge IPs in internal-debug builds

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Exclude `*.orb.local` from Certificate Transparency (internal-debug only)

**Files:**
- Modify: `app/features/src/main/kotlin/de/gematik/ti/erp/app/di/ClientBuilderModule.kt:140-145`

- [ ] **Step 1: Add the exclusion**

Replace:
```kotlin
fun OkHttpClient.Builder.addCertificateTransparencyInterceptor() =
    addNetworkInterceptor(
        certificateTransparencyInterceptor {
            failOnError = true
        }
    )
```
with:
```kotlin
fun OkHttpClient.Builder.addCertificateTransparencyInterceptor() =
    addNetworkInterceptor(
        certificateTransparencyInterceptor {
            failOnError = true
            // OrbStack's local dev certificate is not CT-logged; skip CT for the local TU stack.
            if (BuildConfigExtension.isInternalDebug) {
                -"*.orb.local"
            }
        }
    )
```
(`-"host"` is the Appmattus `certificatetransparency-android` DSL operator for excludeHosts; `*.orb.local` matches all `*.orb.local` subdomains. `BuildConfigExtension` is already imported in this file.)

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:features:compileGoogleTuInternalDebugKotlin
```
Expected: BUILD SUCCESSFUL. (If `-"*.orb.local"` fails to resolve, the DSL version uses `excludeHosts("*.orb.local")` instead — switch to that and recompile.)

- [ ] **Step 3: Commit**

```bash
git add app/features/src/main/kotlin/de/gematik/ti/erp/app/di/ClientBuilderModule.kt
git commit -m "Exclude *.orb.local from certificate transparency in internal-debug builds

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Trust the OrbStack CA for orb.local in the debug build

**Files:**
- Create: `app/android/src/debug/res/raw/orbstack_dev_ca.pem`
- Create: `app/android/src/debug/res/xml/network_security_config.xml`

- [ ] **Step 1: Export the OrbStack CA into the debug raw resources**

```bash
cd /Users/dsev001/projects/dsev001/E-Rezept-App-Android/.claude/worktrees/quizzical-knuth-a6c27d
mkdir -p app/android/src/debug/res/raw app/android/src/debug/res/xml
security find-certificate -p -c "OrbStack Development Root CA" > app/android/src/debug/res/raw/orbstack_dev_ca.pem
openssl x509 -in app/android/src/debug/res/raw/orbstack_dev_ca.pem -noout -subject
```
Expected: `subject=O=OrbStack Development, OU=Containers & Services, CN=OrbStack Development Root CA`.

- [ ] **Step 2: Create the debug network-security-config override**

Create `app/android/src/debug/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false"/>
    <!-- Debug-only: trust the OrbStack Development Root CA for the local TU stack (*.orb.local). -->
    <domain-config>
        <domain includeSubdomains="true">orb.local</domain>
        <trust-anchors>
            <certificates src="system"/>
            <certificates src="@raw/orbstack_dev_ca"/>
        </trust-anchors>
    </domain-config>
</network-security-config>
```
(This file overrides `app/android/src/main/res/xml/network_security_config.xml` for the debug build type only; release builds keep the main config. The manifest's `@xml/network_security_config` reference is unchanged.)

- [ ] **Step 3: Verify the debug resource is picked up (build the APK)**

```bash
./gradlew :app:android:assembleGoogleTuInternalDebug
```
Expected: BUILD SUCCESSFUL. (Confirms the raw PEM resource and xml parse correctly.)

- [ ] **Step 4: Commit**

```bash
git add app/android/src/debug/res/raw/orbstack_dev_ca.pem app/android/src/debug/res/xml/network_security_config.xml
git commit -m "Trust OrbStack dev CA for *.orb.local in debug network-security-config

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: End-to-end verification in the emulator

No code changes. Confirms the whole chain (DNS override → routing → OrbStack TLS trust → CT skip) for both FD and IDP.

- [ ] **Step 1: Confirm the stack is up and the IPs still match**

```bash
curl -s -o /dev/null -w "FD https %{http_code}\n" --max-time 6 https://fachdienst.vibrant-kalam-3e8bca.orb.local/Health
curl -s -o /dev/null -w "IDP https %{http_code}\n" --max-time 6 https://idp-server.ref-idp-server.orb.local/.well-known/openid-configuration
for h in fachdienst.vibrant-kalam-3e8bca.orb.local idp-server.ref-idp-server.orb.local; do printf "%s=" "$h"; dscacheutil -q host -a name "$h" | awk '/^ip_address/{print $2; exit}'; done
```
Expected: both HTTP 200, and the IPs match the `ORB_LOCAL_DNS_OVERRIDES` line from Task 1. If an IP changed, update both ci-overrides copies (Task 1 Step 4) and rebuild before continuing.

- [ ] **Step 2: Confirm the emulator can TCP-reach the bridge IPs**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB shell 'toybox nc -w 3 192.168.138.15 443 < /dev/null && echo FD_443_OPEN || echo FD_443_FAIL'
$ADB shell 'toybox nc -w 3 192.168.138.3 443 < /dev/null && echo IDP_443_OPEN || echo IDP_443_FAIL'
```
Expected: both `*_OPEN` (use the actual IPs from Step 1). If a host fails, slirp routing to that IP is the problem — stop and report.

- [ ] **Step 3: Install the freshly built app**

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/android/build/outputs/apk/googleTuInternal/debug/android-googleTuInternal-debug.apk
```
Expected: `Success`.

- [ ] **Step 4: Watch logs and exercise the virtual health card login**

Start a filtered logcat, then on the emulator open the app → Settings → "Nerd control room" → "Secret switches" → scroll to Virtual Health Card (fields are prefilled from BuildKonfig) → tap the login button:
```bash
~/Library/Android/sdk/platform-tools/adb logcat -c
~/Library/Android/sdk/platform-tools/adb logcat | grep -iE "orb.local|UnknownHostException|VAUCertificate|PKIX|certificate transparency|SSLHandshake|sso_token|access_token"
```
Expected:
- NO `UnknownHostException` for any `orb.local` host.
- NO `PKIX`/`SSLHandshakeException` (CA trusted) and NO certificate-transparency failure (CT excluded).
- The FD `/VAUCertificate` call and the IDP discovery/token calls proceed; a virtual-card login completes (token obtained) or fails only at a later business-logic step — record whatever the actual outcome is.

If `UnknownHostException` still appears: the override host key doesn't exactly match the requested hostname — compare the logged host against the `ORB_LOCAL_DNS_OVERRIDES` keys and `BASE_SERVICE_URI_TU` (Task 1). If PKIX/CT errors appear: re-check Task 4 (CA) / Task 3 (CT) respectively. Report the exact error rather than guessing.

---

### Final state

- Tracked commits: Task 1 (`common/build.gradle.kts`), Task 2 (BlockingDns + test + DI), Task 3 (CT), Task 4 (debug netsec + CA). All gated to `isInternalDebug` or the `debug` source set — **release builds are byte-for-byte unaffected** (empty `ORB_LOCAL_DNS_OVERRIDES`, no CT exclusion, main network-security-config).
- Machine-local: the `ORB_LOCAL_DNS_OVERRIDES` line in both `ci-overrides.properties` copies (gitignored).
- The emulator app now reaches the local TU stack over real HTTPS with the real orb.local hostnames and OrbStack certs. When containers are recreated and IPs change, update the `ORB_LOCAL_DNS_OVERRIDES` line and reinstall.
