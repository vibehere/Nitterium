package com.kaleedtc.nitterium

import com.kaleedtc.nitterium.data.frontend.FrontendType
import com.kaleedtc.nitterium.data.frontend.InstanceSettingsCookies
import com.kaleedtc.nitterium.data.model.*
import org.junit.Assert.*
import org.junit.Test

class InstanceSettingsCookiesTest {
    @Test fun defaultsMatchSharedAppearanceAndFrontendDifferences() {
        val teapot = InstanceSettingsCookies.read("", FrontendType.TEAPOT)
        assertEquals(true, teapot.stickyProfile)
        assertEquals(true, teapot.squareAvatars)
        assertTrue(teapot.autoplayGifs)
        assertFalse(teapot.proxyVideos)
        assertTrue(teapot.infiniteScroll)
        assertEquals(false, teapot.teapotHlsPlayback)
        val nitter = InstanceSettingsCookies.read("", FrontendType.NITTER)
        assertEquals(true, nitter.stickyProfile)
        assertEquals(false, nitter.squareAvatars)
        assertTrue(nitter.autoplayGifs)
        assertFalse(nitter.proxyVideos)
        assertTrue(nitter.infiniteScroll)
        assertNull(nitter.teapotHlsPlayback)
    }

    @Test fun teapotUsesIndependentPlaybackCookiesAndMediaProxy() {
        // Matches the read-only cookie check against xitter.cf/settings.
        val settings = InstanceSettingsCookies.read(
            "stickyProfile=off; squareAvatars=off; mp4Playback=off; hlsPlayback=on; proxyMedia=on; proxyVideos=off",
            FrontendType.TEAPOT
        )
        assertEquals(false, settings.stickyProfile)
        assertEquals(false, settings.squareAvatars)
        assertFalse(settings.hlsPlayback) // Existing backup field represents Teapot's MP4 option.
        assertEquals(true, settings.teapotHlsPlayback)
        assertTrue(settings.proxyVideos)
        val cookies = InstanceSettingsCookies.values(settings, FrontendType.TEAPOT)
        assertEquals(false, cookies["mp4Playback"])
        assertEquals(true, cookies["hlsPlayback"])
        assertEquals(true, cookies["proxyMedia"])
        assertFalse(cookies.containsKey("proxyVideos"))
    }

    @Test fun nitterRetainsItsPlaybackAndProxyKeys() {
        val settings = InstanceSettingsCookies.read(
            "hlsPlayback=off; proxyVideos=on; proxyMedia=off; stickyProfile=off; squareAvatars=on", FrontendType.NITTER
        )
        assertFalse(settings.hlsPlayback)
        assertTrue(settings.proxyVideos)
        assertEquals(false, settings.stickyProfile)
        assertEquals(true, settings.squareAvatars)
        val cookies = InstanceSettingsCookies.values(settings, FrontendType.NITTER)
        assertEquals(false, cookies["hlsPlayback"])
        assertEquals(true, cookies["proxyVideos"])
        assertFalse(cookies.containsKey("mp4Playback"))
        assertFalse(cookies.containsKey("proxyMedia"))
    }

    @Test fun readsExactCookieNamesAndSeedsOnlyMissingDefaults() {
        val settings = InstanceSettingsCookies.read("oldhideBanner=on; hideBanner=off; hidePins=only; broken", FrontendType.TEAPOT)
        assertFalse(settings.hideBanner)
        assertFalse(settings.hidePins)
        assertTrue(
            InstanceSettingsCookies.missingDefaults(
                "mp4Playback=off; proxyMedia=off; infiniteScroll=on",
                FrontendType.TEAPOT
            ).isEmpty()
        )
        assertEquals(
            mapOf("mp4Playback" to true, "infiniteScroll" to true),
            InstanceSettingsCookies.missingDefaults("oldmp4Playback=on; proxyMedia=off", FrontendType.TEAPOT)
        )
    }

    @Test fun proxyDefaultsAreOffAndNeverReplaceSavedChoices() {
        for (frontend in listOf(FrontendType.NITTER, FrontendType.TEAPOT)) {
            val adapter = com.kaleedtc.nitterium.data.frontend.FrontendAdapter.forType(frontend)
            assertEquals(
                mapOf(
                    adapter.playbackCookie to true,
                    adapter.proxyCookie to false,
                    "infiniteScroll" to true
                ),
                InstanceSettingsCookies.missingDefaults("", frontend)
            )
            assertEquals(
                mapOf(adapter.playbackCookie to true, "infiniteScroll" to true),
                InstanceSettingsCookies.missingDefaults("${adapter.proxyCookie}=on", frontend)
            )
        }
        assertEquals(mapOf("proxyVideos" to false, "proxyMedia" to false),
            InstanceSettingsCookies.missingProxyDefaults(""))
        assertEquals(mapOf("proxyMedia" to false),
            InstanceSettingsCookies.missingProxyDefaults("proxyVideos=on; oldproxyMedia=on"))
        assertTrue(InstanceSettingsCookies.missingProxyDefaults("proxyVideos=off; proxyMedia=on").isEmpty())
    }

    @Test fun nativeTogglesUseWhitelistedKeysWithoutChangingOtherOptions() {
        val original = InstanceSettingsCookies.read("mp4Playback=off; hlsPlayback=on", FrontendType.TEAPOT)
        val changed = InstanceSettingsCookies.update(original, "squareAvatars", false)!!
        assertEquals(original.copy(squareAvatars = false), changed)
        assertEquals("stickyProfile", InstanceSettingsCookies.cookieKey("stickyProfile", FrontendType.TEAPOT))
        assertEquals("squareAvatars", InstanceSettingsCookies.cookieKey("squareAvatars", FrontendType.NITTER))
        assertEquals("mp4Playback", InstanceSettingsCookies.cookieKey("hlsPlayback", FrontendType.TEAPOT))
        assertEquals("hlsPlayback", InstanceSettingsCookies.cookieKey("teapotHlsPlayback", FrontendType.TEAPOT))
        assertEquals("hideRetweets", InstanceSettingsCookies.cookieKey("hideRetweets", FrontendType.NITTER))
        assertNull(InstanceSettingsCookies.cookieKey("teapotHlsPlayback", FrontendType.NITTER))
        assertNull(InstanceSettingsCookies.cookieKey("kagiToken", FrontendType.TEAPOT))
        assertNull(InstanceSettingsCookies.update(original, "unknown", true))
    }

    @Test fun hideRetweetsCookieRoundTripsAndDefaultsOff() {
        assertFalse(InstanceSettingsCookies.read("", FrontendType.TEAPOT).hideRetweets)
        val enabled = InstanceSettingsCookies.read("hideRetweets=on", FrontendType.NITTER)
        assertTrue(enabled.hideRetweets)
        assertEquals(true, InstanceSettingsCookies.values(enabled, FrontendType.NITTER)["hideRetweets"])
        val toggled = InstanceSettingsCookies.update(enabled, "hideRetweets", false)!!
        assertFalse(toggled.hideRetweets)
        assertEquals(false, InstanceSettingsCookies.values(toggled, FrontendType.TEAPOT)["hideRetweets"])
    }

    @Test fun hideRepliesAndCommunityNotesRoundTrip() {
        assertFalse(InstanceSettingsCookies.read("", FrontendType.NITTER).hideReplies)
        assertFalse(InstanceSettingsCookies.read("", FrontendType.NITTER).hideCommunityNotes)
        val enabled = InstanceSettingsCookies.read(
            "hideReplies=on; hideCommunityNotes=on", FrontendType.TEAPOT
        )
        assertTrue(enabled.hideReplies)
        assertTrue(enabled.hideCommunityNotes)
        val cookies = InstanceSettingsCookies.values(enabled, FrontendType.TEAPOT)
        assertEquals(true, cookies["hideReplies"])
        assertEquals(true, cookies["hideCommunityNotes"])
        assertEquals(
            false,
            InstanceSettingsCookies.update(enabled, "hideReplies", false)!!.hideReplies
        )
    }

    @Test fun newOptionsRoundTripAndOldBackupsLeaveNewCookiesUntouched() {
        val options = InstanceSettingsCookies.read("stickyProfile=off; squareAvatars=on; mp4Playback=off; hlsPlayback=on", FrontendType.TEAPOT)
        val backup = NitteriumBackup(settings = AppSettingsBackup(instanceOptions = mapOf(
            "https://xitter.cf" to InstanceOptionsBackup(FrontendType.TEAPOT, options)
        )))
        assertEquals(backup, BackupCodec.decode(BackupCodec.encode(backup, BackupScope.SETTINGS), "https://xitter.cf"))
        val legacy = BackupCodec.decode("""{
            "format":"nitterium-backup","version":1,"settings":{
              "instanceOptions":{"https://xitter.cf":{"frontend":"TEAPOT","options":{"hlsPlayback":false}}}
            }}""", "https://xitter.cf").settings!!.instanceOptions.getValue("https://xitter.cf").options
        val cookies = InstanceSettingsCookies.values(legacy, FrontendType.TEAPOT)
        assertEquals(false, cookies["mp4Playback"])
        assertEquals(false, cookies["proxyMedia"])
        assertFalse(cookies.containsKey("hlsPlayback"))
        assertFalse(cookies.containsKey("stickyProfile"))
        assertFalse(cookies.containsKey("squareAvatars"))
    }
}
