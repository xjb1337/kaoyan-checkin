package com.xjb.kaoyancheckin

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateClientTest {
    private val apk = "abc".toByteArray()
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val manifest = UpdateManifest(4, "0.4.0", "https://example.com/app.apk", hash, "说明")

    private class FakeConnection(url: String, private val bytes: ByteArray, private val status: Int = 200, private val readError: IOException? = null) : HttpURLConnection(URL(url)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = bytes.size.toLong()
        override fun getInputStream(): InputStream { readError?.let { throw it }; return ByteArrayInputStream(bytes) }
    }

    @Test fun releaseWithoutManifestDoesNotFetchOrOfferUpgrade() = runBlocking {
        var requests = 0
        val client = UpdateClient { url ->
            requests++
            FakeConnection(url, """{"assets":[{"name":"old.apk"}]}""".toByteArray())
        }
        assertEquals(UpdateCheck.NoManifest, client.check(3))
        assertEquals(1, requests)
    }

    @Test fun downloadedApkIsVerifiedAndFailureDeletesPartialFile() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkin-update").toFile()
        val updates = File(directory, "updates")
        try {
            val client = UpdateClient { FakeConnection(it, apk) }
            val file = client.download(manifest, updates) { _, _ -> }
            assertEquals("abc", file.readText())
            try {
                client.download(manifest.copy(sha256 = "0".repeat(64)), updates) { _, _ -> }
                fail("checksum must fail")
            } catch (e: UpdateException) { assertEquals(UpdateFailure.CHECKSUM, e.failure) }
            assertFalse(file.exists())
            assertFalse(File(directory, "update-download.part").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun timeoutAndStorageErrorsAreReported() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("checkin-errors").toFile()
        try {
            val timeout = UpdateClient { FakeConnection(it, apk, readError = SocketTimeoutException()) }
            try { timeout.download(manifest, File(directory, "updates")) { _, _ -> }; fail("timeout expected") }
            catch (e: UpdateException) { assertEquals(UpdateFailure.TIMEOUT, e.failure) }
            val empty = UpdateClient { FakeConnection(it, byteArrayOf()) }
            try { empty.download(manifest, File(directory, "updates")) { _, _ -> }; fail("empty APK expected") }
            catch (e: UpdateException) { assertEquals(UpdateFailure.MISSING_APK, e.failure) }
            val badDirectory = File(directory, "not-a-directory").apply { writeText("x") }
            val client = UpdateClient { FakeConnection(it, apk) }
            try { client.download(manifest, badDirectory) { _, _ -> }; fail("storage error expected") }
            catch (e: UpdateException) { assertEquals(UpdateFailure.STORAGE, e.failure) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun non200StatusIsReported() = runBlocking {
        val client = UpdateClient { FakeConnection(it, byteArrayOf(), 503) }
        try { client.check(3); fail("HTTP error expected") }
        catch (e: UpdateException) { assertEquals(UpdateFailure.HTTP, e.failure) }
    }
}
