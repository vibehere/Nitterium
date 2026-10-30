package com.kaleedtc.nitterium

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kaleedtc.nitterium.data.model.AppSettingsBackup
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
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

class InstanceAccessKeyTest {
    @Test fun keysAreScopedToHostAndSanitizedForUserAgent() = runBlocking {
        val directory = Files.createTempDirectory("nitterium-instance-key").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                directory.resolve("keys.preferences_pb")
            }
            val preferences = UserPreferencesRepository(store)
            preferences.setInstanceKey("https://private.example/path", "abc_123-XYZ!!")
            assertEquals(
                mapOf("private.example" to "abc_123-XYZ"),
                preferences.instanceKeys.first()
            )
            assertEquals(
                "abc_123-XYZ",
                preferences.instanceKeyFor("https://private.example").first()
            )
            assertEquals(
                "",
                preferences.instanceKeyFor("https://other.example").first()
            )
            assertEquals(
                "Mozilla Nitterium/abc_123-XYZ",
                UserPreferencesRepository.withAccessKey("Mozilla", "abc_123-XYZ")
            )
            assertEquals(
                "Mozilla",
                UserPreferencesRepository.withAccessKey("Mozilla Nitterium/old", "")
            )
            preferences.restoreSettings(
                AppSettingsBackup(instanceKeys = mapOf("backup.example" to "token1"))
            )
            assertEquals(
                mapOf("backup.example" to "token1"),
                preferences.backupSettings().instanceKeys
            )
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test fun hostOfIgnoresPathAndCase() {
        assertEquals(
            "nitter.example",
            UserPreferencesRepository.hostOf("https://Nitter.Example/alice")
        )
        assertFalse(UserPreferencesRepository.sanitizeInstanceKey("***").isNotEmpty())
        assertTrue(UserPreferencesRepository.sanitizeInstanceKey("ok_1").isNotEmpty())
    }
}
