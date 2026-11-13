package eu.kanade.tachiyomi.data.download

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutomaticCloudUploadNetworkTest {
    @Test
    fun `only Wi-Fi is accepted when the preference is enabled`() {
        assertFalse(automaticUploadNetworkAllowed(connected = false, isWifi = false, wifiOnly = true))
        assertFalse(automaticUploadNetworkAllowed(connected = true, isWifi = false, wifiOnly = true))
        assertTrue(automaticUploadNetworkAllowed(connected = true, isWifi = true, wifiOnly = true))
    }

    @Test
    fun `any connected network is accepted when the preference is disabled`() {
        assertFalse(automaticUploadNetworkAllowed(connected = false, isWifi = false, wifiOnly = false))
        assertTrue(automaticUploadNetworkAllowed(connected = true, isWifi = false, wifiOnly = false))
        assertTrue(automaticUploadNetworkAllowed(connected = true, isWifi = true, wifiOnly = false))
    }
}
