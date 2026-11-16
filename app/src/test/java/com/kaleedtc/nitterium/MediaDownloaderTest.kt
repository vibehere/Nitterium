package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.security.MediaDownloader
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDownloaderTest {

    @Test fun namesFileAfterProxiedVideoSegment() {
        val url = "https://nitter.example/video/1C17F/https%3A%2F%2Fvideo.twimg.com%2Famplify_video%2F212%2Fvid%2Favc1%2F400x620%2Fq-bpodsYk.mp4%3Ftag%3D29"

        assertEquals("Nitterium_q-bpodsYk.mp4", MediaDownloader.videoFileName(url, 7))
    }

    @Test fun stripsUnsafeCharactersFromName() {
        assertEquals(
            "Nitterium_a_b.mp4",
            MediaDownloader.videoFileName("https://nitter.example/video/K/a%20b.mp4", 7)
        )
    }

    @Test fun fallsBackToIdWhenUrlHasNoName() {
        assertEquals("Nitterium_video_7.mp4", MediaDownloader.videoFileName("https://nitter.example/", 7))
    }
}
