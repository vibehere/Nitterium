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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedSpreadPreferencesTest {
    @Test fun spreadToggleAndFeedPrefsRoundTripThroughBackup() = runBlocking {
        val directory = createTempDirectory("feed-spread-prefs").toFile()
        val job = Job()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
                File(directory, "prefs.preferences_pb")
            }
            val repo = UserPreferencesRepository(store)
            assertFalse(repo.feedSpreadAcrossInstances.first())
            assertTrue(repo.feedAutoLoadBatches.first())
            assertEquals(10, repo.feedUsersPerBatch.first())

            repo.setFeedUsersPerBatch(7)
            repo.setFeedAutoLoadBatches(false)
            repo.setFeedSpreadAcrossInstances(true)

            val backup = repo.backupSettings()
            assertEquals(7, backup.feedUsersPerBatch)
            assertFalse(backup.feedAutoLoadBatches)
            assertTrue(backup.feedSpreadAcrossInstances)

            repo.setFeedUsersPerBatch(100)
            assertEquals(50, repo.feedUsersPerBatch.first())
            repo.setFeedUsersPerBatch(0)
            assertEquals(1, repo.feedUsersPerBatch.first())
            repo.setFeedUsersPerBatch(10)
            repo.setFeedAutoLoadBatches(true)
            repo.setFeedSpreadAcrossInstances(false)
            repo.restoreSettings(backup)

            assertEquals(7, repo.feedUsersPerBatch.first())
            assertFalse(repo.feedAutoLoadBatches.first())
            assertTrue(repo.feedSpreadAcrossInstances.first())
        } finally {
            job.cancel()
            directory.deleteRecursively()
        }
    }
}
