package dev.motonav.simulator.routing

import dev.motonav.simulator.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialPackagingTest {
    @Test fun `release builds are always fixture only`() {
        if (!BuildConfig.DEBUG) assertTrue(BuildConfig.MAPS_API_KEY.isEmpty())
    }
}
