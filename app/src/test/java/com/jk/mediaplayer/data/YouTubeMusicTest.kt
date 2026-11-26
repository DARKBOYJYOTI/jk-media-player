package com.jk.mediaplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicTest {

    @Test
    fun `remote ids never collide with MediaStore ids`() {
        val id = YouTubeMusic.remoteTrackId("dQw4w9WgXcQ")
        assertTrue(id >= 2_000_000_000L)
    }

    @Test
    fun `remote ids are deterministic`() {
        assertEquals(
            YouTubeMusic.remoteTrackId("dQw4w9WgXcQ"),
            YouTubeMusic.remoteTrackId("dQw4w9WgXcQ")
        )
    }

    @Test
    fun `different videos get different ids`() {
        assertNotEquals(
            YouTubeMusic.remoteTrackId("dQw4w9WgXcQ"),
            YouTubeMusic.remoteTrackId("9bZkp7q19f0")
        )
    }

    @Test
    fun `videoIdOf round trips a ytm path`() {
        assertEquals("dQw4w9WgXcQ", YouTubeMusic.videoIdOf("ytm:dQw4w9WgXcQ"))
    }

    @Test
    fun `videoIdOf returns null for local paths`() {
        assertNull(YouTubeMusic.videoIdOf("/storage/emulated/0/Music/a.mp3"))
    }

    @Test
    fun `videoIdOf returns null for empty ytm path`() {
        assertNull(YouTubeMusic.videoIdOf("ytm:"))
    }

    @Test
    fun `remote path round trips through videoIdOf`() {
        val path = YouTubeMusic.remotePath("abc")
        assertEquals("ytm:abc", path)
        assertEquals("abc", YouTubeMusic.videoIdOf(path))
    }

    @Test
    fun `thumbnail fallback points at the video id`() {
        assertEquals(
            "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            YouTubeMusic.thumbnailFallback("abc")
        )
    }

    @Test
    fun `music cdn artwork is requested at a sharp size`() {
        assertEquals(
            "https://lh3.googleusercontent.com/cover=w544-h544-l90-rj",
            YouTubeMusic.upgradeArtwork("https://lh3.googleusercontent.com/cover=w60-h60-l90-rj")
        )
        assertEquals(
            "https://yt3.googleusercontent.com/cover=w544-h544-l90-rj",
            YouTubeMusic.upgradeArtwork("https://yt3.googleusercontent.com/cover=w120-h120-l90-rj")
        )
    }

    @Test
    fun `non music artwork urls are left alone`() {
        val url = "https://i.ytimg.com/vi/abc/hqdefault.jpg"
        assertEquals(url, YouTubeMusic.upgradeArtwork(url))
    }

    @Test
    fun `legacy square artwork param is upgraded`() {
        assertEquals(
            "https://lh3.googleusercontent.com/cover=w544-h544-c",
            YouTubeMusic.upgradeArtwork("https://lh3.googleusercontent.com/cover=s120-c")
        )
    }
}
