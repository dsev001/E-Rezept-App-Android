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
    fun `state uses saved virtual health card when present`() {
        every { virtualHealthCardDataStore.getCert() } returns "saved-cert"
        every { virtualHealthCardDataStore.getPrivateKey() } returns "saved-key"

        testScope.runTest {
            viewModel.state()

            assertEquals("saved-cert", viewModel.debugSettingsData.virtualHealthCardCert)
            assertEquals("saved-key", viewModel.debugSettingsData.virtualHealthCardPrivateKey)
        }
    }
}
