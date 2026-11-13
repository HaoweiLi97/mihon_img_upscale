package eu.kanade.tachiyomi.data.download

import android.net.NetworkCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class CloudUploadWorkConstraintsTest {
    @Test
    fun wifiOnlyWorkRequiresWifiTransport() {
        val network = checkNotNull(CloudUploadWorker.constraints(wifiOnly = true).requiredNetworkRequest)

        assertTrue(network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        assertFalse(network.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    @Test
    fun workWithoutWifiOnlyRequiresAnActiveNetwork() {
        val constraints = CloudUploadWorker.constraints(wifiOnly = false)

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertNull(constraints.requiredNetworkRequest)
    }
}
