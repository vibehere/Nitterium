package com.kaleedtc.nitterium

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kaleedtc.nitterium.data.model.AppSettingsBackup
import com.kaleedtc.nitterium.data.model.SocksProxySettings
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import com.kaleedtc.nitterium.data.security.PrivacyHeaders
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocksProxySettingsTest {
    @Test fun defaultsAreLocalSocks() {
        val settings = SocksProxySettings()
        assertFalse(settings.enabled)
        assertEquals("127.0.0.1", settings.host)
        assertEquals(9050, settings.port)
        assertTrue(settings.proxyImages)
        assertTrue(settings.isValid())
    }

    @Test fun rejectsBlankHostAndOutOfRangePort() {
        assertFalse(SocksProxySettings(host = " ", port = 9050).isValid())
        assertFalse(SocksProxySettings(host = "127.0.0.1", port = 0).isValid())
        assertFalse(SocksProxySettings(host = "127.0.0.1", port = 70000).isValid())
    }

    @Test fun preferencesRoundTripThroughBackup() = runBlocking {
        val directory = Files.createTempDirectory("nitterium-socks").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                directory.resolve("socks.preferences_pb")
            }
            val preferences = UserPreferencesRepository(store)
            assertEquals(SocksProxySettings(), preferences.socksProxy.first())
            val updated = SocksProxySettings(
                enabled = true,
                host = "127.0.0.1",
                port = 9150,
                proxyImages = false
            )
            preferences.setSocksProxy(updated)
            assertEquals(updated, preferences.socksProxy.first())
            assertEquals(updated, preferences.backupSettings().socksProxy())
            preferences.restoreSettings(
                AppSettingsBackup(
                    socksProxyEnabled = true,
                    socksProxyHost = "10.0.0.2",
                    socksProxyPort = 9050,
                    socksProxyImages = true
                )
            )
            assertEquals(
                SocksProxySettings(true, "10.0.0.2", 9050, true),
                preferences.socksProxy.first()
            )
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test fun acceptLanguageIsFixedEnglish() {
        assertEquals("en-US,en;q=0.9", PrivacyHeaders.ACCEPT_LANGUAGE)
        assertEquals(
            mapOf("Accept-Language" to PrivacyHeaders.ACCEPT_LANGUAGE),
            PrivacyHeaders.navigationHeaders()
        )
        assertEquals("en-US", PrivacyHeaders.NAVIGATOR_LANGUAGE)
        val script = PrivacyHeaders.navigatorLocaleScript()
        assertTrue(script.contains("en-US"))
        assertTrue(script.contains("Navigator.prototype"))
        assertTrue(script.contains("languages"))
    }
}
