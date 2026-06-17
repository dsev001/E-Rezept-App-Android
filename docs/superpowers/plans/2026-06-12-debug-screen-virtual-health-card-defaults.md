# Debug Screen Virtual Health Card Defaults Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create `ci-overrides.properties` in the worktree and make the Debug Screen pre-fill its "Virtual Health Card" certificate & private key fields from `BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_CERTIFICATE` / `_PRIVATE_KEY`.

**Architecture:** `ci-overrides.properties` (gitignored, repo root) feeds the `overrides()` Gradle property delegate (`buildSrc/.../DependenciesPlugin.kt`), which `common/build.gradle.kts` uses to bake `DEFAULT_VIRTUAL_HEALTH_CARD_CERTIFICATE` / `_PRIVATE_KEY` into the generated `BuildKonfig` object. The Debug Screen fields bind to `DebugSettingsViewModel.debugSettingsData.virtualHealthCardCert/PrivateKey`, which `state()` fills from: in-memory value → saved datastore value → SSO-token cert (cert only) → currently `""`. We extend that last fallback to use the `BuildKonfig` defaults, keeping all existing precedence intact.

**Tech Stack:** Kotlin, Gradle (buildkonfig plugin), JUnit 4 + mockk + kotlinx-coroutines-test (existing patterns in `app/features/src/test`).

**Background facts (verified):**
- `ci-overrides.properties` is gitignored (`.gitignore:48`) and absent from the worktree. The main checkout (`/Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties`) already contains the full file including the exact two virtual-health-card values requested, plus TU/orb.local URLs the user's local builds need (`BASE_SERVICE_URI_TU`, `IDP_SERVICE_URI_TU`, etc. — these have NO defaults in `gradle.properties`, so a partial file would break TU builds).
- Resolution order per property: `ci-overrides.properties` → Gradle project properties → loaded `gradle.properties` → `""` (see `buildSrc/src/main/kotlin/de/gematik/ti/erp/app/plugins/dependencies/DependenciesPlugin.kt:48-64`).
- `DebugSettingsViewModel` is in `app/features/src/main`, can access `BuildKonfig` (e.g. `app/features/src/main/kotlin/de/gematik/ti/erp/app/TestWrapper.kt:77-78` already does).
- The Debug Screen calls `viewModel.state()` in a `LaunchedEffect` on open (`app/features/src/debug/kotlin/de/gematik/ti/erp/app/ui/DebugScreen.kt:614-615`), so the fallback in `state()` is what pre-fills the fields.
- `app/features` has no product flavors; unit test task is `:app:features:testDebugUnitTest`.

---

### Task 1: Create `ci-overrides.properties` in the worktree

The file is gitignored — nothing gets committed in this task. Copy the full file from the main checkout (it already contains the requested `DEFAULT_VIRTUAL_HEALTH_CARD_CERTIFICATE` and `DEFAULT_VIRTUAL_HEALTH_CARD_PRIVATE_KEY` values verbatim, plus the TU overrides local builds require).

**Files:**
- Create: `ci-overrides.properties` (worktree root, gitignored)

- [ ] **Step 1: Copy the file from the main checkout**

```bash
cp /Users/dsev001/projects/dsev001/E-Rezept-App-Android/ci-overrides.properties \
   /Users/dsev001/projects/dsev001/E-Rezept-App-Android/.claude/worktrees/quizzical-knuth-a6c27d/ci-overrides.properties
```

- [ ] **Step 2: Verify the two required keys are present**

Run:
```bash
grep -c "DEFAULT_VIRTUAL_HEALTH_CARD_CERTIFICATE=MIIC/TCCAqOgAwIBAgIHAzA3awZuoDAKBggq" ci-overrides.properties
grep -c "DEFAULT_VIRTUAL_HEALTH_CARD_PRIVATE_KEY=EVl39AiTIPIXzfNvM+fRXXBx9uo6+ztsiCiYclJpeQw=" ci-overrides.properties
```
Expected: `1` for each.

- [ ] **Step 3: Verify git ignores it**

Run: `git status --porcelain ci-overrides.properties`
Expected: empty output (file is ignored, nothing to commit).

---

### Task 2: Write the failing unit test for the BuildKonfig fallback

**Files:**
- Test (create): `app/features/src/test/kotlin/de/gematik/ti/erp/app/debugsettings/presentation/DebugSettingsViewModelTest.kt`

Pattern reference: `app/features/src/test/kotlin/de/gematik/ti/erp/app/userauthentication/UserAuthenticationControllerTest.kt` (StandardTestDispatcher + `Dispatchers.setMain` + mockk).

Notes on the mocks:
- `DebugSettingsViewModel` has 26 constructor params; all irrelevant ones are `mockk(relaxed = true)`.
- Flows read with `.first()` inside `state()` MUST be explicitly stubbed (`profilesUseCase.activeProfileId()`, `cardWallUseCase.authenticationData()`, `idpRepository.decryptedAccessToken()`) — a relaxed Flow mock makes `.first()` throw `NoSuchElementException`.
- The first test uses `assumeTrue` on the BuildKonfig constant so it is skipped (not red) in environments whose `ci-overrides.properties` lacks the virtual-health-card keys.

- [ ] **Step 1: Create the test file with this exact content**

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

package de.gematik.ti.erp.app.debugsettings.presentation

import de.gematik.ti.erp.app.BuildKonfig
import de.gematik.ti.erp.app.cardwall.usecase.CardWallUseCase
import de.gematik.ti.erp.app.database.datastore.virtualhealthcard.VirtualHealthCardLocalDataSource
import de.gematik.ti.erp.app.idp.model.IdpData
import de.gematik.ti.erp.app.idp.repository.IdpRepository
import de.gematik.ti.erp.app.profiles.usecase.ProfilesUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DebugSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    private val cardWallUseCase: CardWallUseCase = mockk()
    private val profilesUseCase: ProfilesUseCase = mockk()
    private val idpRepository: IdpRepository = mockk()
    private val virtualHealthCardDataStore: VirtualHealthCardLocalDataSource = mockk()

    private lateinit var viewModel: DebugSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)

        every { profilesUseCase.activeProfileId() } returns flowOf("test-profile-id")
        every { cardWallUseCase.authenticationData(any()) } returns
            flowOf(IdpData.AuthenticationData(singleSignOnTokenScope = null))
        every { idpRepository.decryptedAccessToken(any()) } returns flowOf(null)
        every { virtualHealthCardDataStore.getCert() } returns ""
        every { virtualHealthCardDataStore.getPrivateKey() } returns ""

        viewModel = DebugSettingsViewModel(
            endpointHelper = mockk(relaxed = true),
            cardWallUseCase = cardWallUseCase,
            prescriptionUseCase = mockk(relaxed = true),
            vauRepository = mockk(relaxed = true),
            idpRepository = idpRepository,
            saveInvoiceUseCase = mockk(relaxed = true),
            idpUseCase = mockk(relaxed = true),
            profilesUseCase = profilesUseCase,
            featureToggleRepository = mockk(relaxed = true),
            getAppUpdateManagerFlagUseCase = mockk(relaxed = true),
            changeAppUpdateManagerFlagUseCase = mockk(relaxed = true),
            markAllUnreadMessagesAsReadUseCase = mockk(relaxed = true),
            deletePrescriptionUseCase = mockk(relaxed = true),
            getTaskIdsUseCase = mockk(relaxed = true),
            getIknrUseCase = mockk(relaxed = true),
            updateIknrUseCase = mockk(relaxed = true),
            revokeEuConsentUseCase = mockk(relaxed = true),
            consentVersionDataStore = mockk(relaxed = true),
            communicationVersionDataStore = mockk(relaxed = true),
            communicationDigaVersionDataStore = mockk(relaxed = true),
            euVersionDataStore = mockk(relaxed = true),
            getAndroid8DeprecationOverrideUseCase = mockk(relaxed = true),
            setAndroid8DeprecationOverrideUseCase = mockk(relaxed = true),
            resetOnboardingUseCase = mockk(relaxed = true),
            virtualHealthCardPrivateKeyDataStore = virtualHealthCardDataStore,
            dispatchers = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `state falls back to BuildKonfig defaults for virtual health card`() {
        assumeTrue(BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_CERTIFICATE.isNotEmpty())
        assumeTrue(BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_PRIVATE_KEY.isNotEmpty())

        testScope.runTest {
            viewModel.state()

            assertEquals(
                BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_CERTIFICATE,
                viewModel.debugSettingsData.virtualHealthCardCert
            )
            assertEquals(
                BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_PRIVATE_KEY,
                viewModel.debugSettingsData.virtualHealthCardPrivateKey
            )
        }
    }

    @Test
    fun `state prefers saved virtual health card over BuildKonfig defaults`() {
        every { virtualHealthCardDataStore.getCert() } returns "saved-cert"
        every { virtualHealthCardDataStore.getPrivateKey() } returns "saved-key"

        testScope.runTest {
            viewModel.state()

            assertEquals("saved-cert", viewModel.debugSettingsData.virtualHealthCardCert)
            assertEquals("saved-key", viewModel.debugSettingsData.virtualHealthCardPrivateKey)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails for the right reason**

Run:
```bash
./gradlew :app:features:testDebugUnitTest --tests "de.gematik.ti.erp.app.debugsettings.presentation.DebugSettingsViewModelTest"
```
Expected: `state falls back to BuildKonfig defaults for virtual health card` FAILS with an `AssertionError` — expected the BuildKonfig certificate string, actual `""` (current code falls back to empty string). `state prefers saved virtual health card over BuildKonfig defaults` PASSES (existing behavior).

If the first test is SKIPPED instead of failing, Task 1 was not completed (BuildKonfig constants are empty) — go back and fix that first.

---

### Task 3: Implement the BuildKonfig fallback in `DebugSettingsViewModel.state()`

**Files:**
- Modify: `app/features/src/main/kotlin/de/gematik/ti/erp/app/debugsettings/presentation/DebugSettingsViewModel.kt` (import block ~line 34; `state()` body lines 217-237)

- [ ] **Step 1: Add the BuildKonfig import**

In the import block, directly after:
```kotlin
import de.gematik.ti.erp.app.BCProvider
```
add:
```kotlin
import de.gematik.ti.erp.app.BuildKonfig
```

- [ ] **Step 2: Extend the fallback chain in `state()`**

Replace (current code at lines 221-227):
```kotlin
        val savedCert = virtualHealthCardPrivateKeyDataStore.getCert()
            .ifEmpty {
                ssoTokenScope?.healthCardCertificate?.let {
                    java.util.Base64.getEncoder().encodeToString(it.encoded)
                } ?: ""
            }
        val savedPrivateKey = virtualHealthCardPrivateKeyDataStore.getPrivateKey()
```
with:
```kotlin
        val savedCert = virtualHealthCardPrivateKeyDataStore.getCert()
            .ifEmpty {
                ssoTokenScope?.healthCardCertificate?.let {
                    java.util.Base64.getEncoder().encodeToString(it.encoded)
                } ?: BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_CERTIFICATE
            }
        val savedPrivateKey = virtualHealthCardPrivateKeyDataStore.getPrivateKey()
            .ifEmpty { BuildKonfig.DEFAULT_VIRTUAL_HEALTH_CARD_PRIVATE_KEY }
```

Resulting precedence (unchanged except the new last resort):
- Certificate: user-entered (in-memory) → saved datastore → SSO-token health card cert → **BuildKonfig default**
- Private key: user-entered (in-memory) → saved datastore → **BuildKonfig default**

- [ ] **Step 3: Run the tests to verify they pass**

Run:
```bash
./gradlew :app:features:testDebugUnitTest --tests "de.gematik.ti.erp.app.debugsettings.presentation.DebugSettingsViewModelTest"
```
Expected: BUILD SUCCESSFUL, both tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/features/src/main/kotlin/de/gematik/ti/erp/app/debugsettings/presentation/DebugSettingsViewModel.kt \
        app/features/src/test/kotlin/de/gematik/ti/erp/app/debugsettings/presentation/DebugSettingsViewModelTest.kt
git commit -m "Prefill debug screen virtual health card fields from BuildKonfig defaults

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

(`ci-overrides.properties` is gitignored and intentionally not committed.)

---

### Task 4: End-to-end verification (manual, on device/emulator)

No code changes. Confirms the whole chain: ci-overrides.properties → BuildKonfig → Debug Screen fields.

- [ ] **Step 1: Build and install the TU debug app**

```bash
./gradlew :app:android:installGoogleTuInternalDebug
```
Expected: BUILD SUCCESSFUL, app installed.

- [ ] **Step 2: Check the Debug Screen fields**

On the device: open the app → Settings → "Nerd control room" → "Secret switches". Scroll to the **Virtual Health Card** card.

Expected: the certificate field is pre-filled with the Base64 string starting `MIIC/TCCAqOgAwIBAgIHAzA3awZuoD...`, the subject info line shows `Juna Fuchs` (CN of the test cert), and the private key field shows `EVl39AiTIPIXzfNvM+fRXXBx9uo6+ztsiCiYclJpeQw=`.

Caveat: if a virtual card was previously saved on that device (datastore non-empty) or the active profile has an SSO token from a card login, those values win by design — to see the pure default, wipe the app's data (or uninstall/reinstall) first.
