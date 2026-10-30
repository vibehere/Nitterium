package com.kaleedtc.nitterium

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.kaleedtc.nitterium.data.repository.UserPreferencesRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MutedWordsPreferencesTest {
    @Test fun mutedWordsRoundTripThroughBackup() = runBlocking {
        val directory = createTempDirectory("muted-words").toFile()
        val job = Job()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
                File(directory, "prefs.preferences_pb")
            }
            val repo = UserPreferencesRepository(store)
            assertTrue(repo.mutedWords.first().isEmpty())
            repo.addMutedWord("spoilers")
            repo.addMutedWord("  SPOILERS ")
            repo.addMutedWord("crypto")
            assertEquals(listOf("crypto", "spoilers"), repo.mutedWords.first())

            val backup = repo.backupSettings()
            assertEquals(listOf("crypto", "spoilers"), backup.mutedWords)

            repo.setMutedWords(emptyList())
            assertTrue(repo.mutedWords.first().isEmpty())
            repo.restoreSettings(backup)
            assertEquals(listOf("crypto", "spoilers"), repo.mutedWords.first())
        } finally {
            job.cancel()
            directory.deleteRecursively()
        }
    }
}
