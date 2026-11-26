package com.holos.reader.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUpdaterTest {

    @Test
    fun exactAbiMatchingDoesNotConfuseX86WithX86_64() {
        val assets = listOf(
            Triple("HolosReader-v5.2-x86_64-release.apk", "https://example.com/x86_64.apk", 100L),
            Triple("HolosReader-v5.2-universal-release.apk", "https://example.com/universal.apk", 120L),
        )
        val pickedForX86 = GitHubUpdater.selectBestApkAsset(assets, arrayOf("x86"))
        assertEquals("HolosReader-v5.2-universal-release.apk", pickedForX86?.first)

        val pickedForX86_64 = GitHubUpdater.selectBestApkAsset(assets, arrayOf("x86_64", "x86"))
        assertEquals("HolosReader-v5.2-x86_64-release.apk", pickedForX86_64?.first)
    }

    @Test
    fun failsClosedWhenNoCompatibleAbiOrUniversalExists() {
        val assets = listOf(
            Triple("HolosReader-v5.2-arm64-v8a-release.apk", "https://example.com/arm64.apk", 100L),
        )
        assertNull(GitHubUpdater.selectBestApkAsset(assets, arrayOf("x86_64")))
    }

    @Test
    fun prefersPrimaryAbiOverUniversal() {
        val assets = listOf(
            Triple("HolosReader-v5.2-universal-release.apk", "https://example.com/universal.apk", 120L),
            Triple("HolosReader-v5.2-arm64-v8a-release.apk", "https://example.com/arm64.apk", 100L),
        )
        val picked = GitHubUpdater.selectBestApkAsset(assets, arrayOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("HolosReader-v5.2-arm64-v8a-release.apk", picked?.first)
    }
}
