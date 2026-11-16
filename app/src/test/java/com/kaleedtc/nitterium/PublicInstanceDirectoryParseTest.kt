package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.PublicInstanceDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicInstanceDirectoryParseTest {
    @Test
    fun scanToleratesUnreachableEndpoints() = runBlocking {
        val result = PublicInstanceDirectory.scanHealthy(
            endpoints = listOf("https://127.0.0.1:1/nope")
        )
        assertTrue(result.healthy.isEmpty())
        assertEquals(0, result.sourcesOk)
        assertEquals(1, result.sourcesTried)
    }
}
