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
