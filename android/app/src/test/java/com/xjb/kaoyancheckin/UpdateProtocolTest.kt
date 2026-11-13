package com.xjb.kaoyancheckin

import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    private val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private fun manifest(code: Int = 4, apk: String = "https://example.com/app.apk", hash: String = digest) =
        """{"versionCode":$code,"versionName":"0.4.0","apkUrl":"$apk","sha256":"$hash","notes":"更新说明"}"""

    @Test fun releaseFindsManifestAndMissingOldReleaseIsNotAnUpgrade() {
        val release = """{"assets":[{"name":"app.apk","browser_download_url":"https://example.com/app.apk"},{"name":"update.json","browser_download_url":"https://example.com/update.json"}]}"""
        assertEquals(ReleaseAsset.Manifest("https://example.com/update.json"), UpdateProtocol.releaseAsset(release))
        assertEquals(ReleaseAsset.Missing, UpdateProtocol.releaseAsset("""{"assets":[{"name":"app.apk"}]}"""))
    }

    @Test fun versionCodeDeterminesUpgrade() {
        val update = UpdateProtocol.parseManifest(manifest())
        assertTrue(UpdateProtocol.isNewer(update, 3))
        assertFalse(UpdateProtocol.isNewer(update, 4))
        assertFalse(UpdateProtocol.isNewer(update, 5))
        assertEquals("更新说明", update.notes)
    }

    @Test fun checksumUsesFileAndAcceptsCaseAndSeparators() {
        val file = File.createTempFile("update-test", ".apk")
        try {
            file.writeText("abc")
            val actual = UpdateProtocol.sha256(file)
            assertEquals(digest, actual)
            assertTrue(UpdateProtocol.checksumMatches(digest.uppercase().chunked(2).joinToString(":"), actual))
            assertTrue(UpdateProtocol.checksumMatches(digest.chunked(2).joinToString("-"), actual.uppercase()))
            assertTrue(UpdateProtocol.checksumMatches(digest.chunked(2).joinToString("\n"), actual))
            assertFalse(UpdateProtocol.checksumMatches("0".repeat(64), actual))
            assertFalse(UpdateProtocol.checksumMatches("not-a-hash", actual))
            file.writeText("damaged")
            assertFalse(UpdateProtocol.checksumMatches(digest, UpdateProtocol.sha256(file)))
        } finally { file.delete() }
    }

    @Test fun damagedJsonMissingFieldsAndMissingApkAreClassified() {
        fun failure(raw: String): UpdateFailure = try { UpdateProtocol.parseManifest(raw); fail("expected error"); error("unreachable") }
            catch (e: UpdateException) { e.failure }
        assertEquals(UpdateFailure.INVALID_DATA, failure("{broken"))
        assertEquals(UpdateFailure.INVALID_DATA, failure("""{"versionCode":4}"""))
        assertEquals(UpdateFailure.INVALID_DATA, failure(manifest(hash = "bad")))
        assertEquals(UpdateFailure.MISSING_APK, failure(manifest(apk = "")))
        assertEquals(UpdateFailure.MISSING_APK, failure(manifest(apk = "http://example.com/app.apk")))
        assertEquals(UpdateFailure.INVALID_DATA, UpdateProtocol.classify(IllegalArgumentException()))
        assertEquals(UpdateFailure.CHECKSUM, UpdateProtocol.classify(UpdateException(UpdateFailure.CHECKSUM, "bad")))
        assertEquals(UpdateFailure.TIMEOUT, UpdateProtocol.classify(SocketTimeoutException()))
        assertEquals(UpdateFailure.NETWORK, UpdateProtocol.classify(UnknownHostException()))
        UpdateFailure.entries.forEach {
            val message = UpdateProtocol.message(it)
            assertTrue(message.endsWith("。"))
            assertFalse(message.contains("手动下载"))
        }
    }
}
