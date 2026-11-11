package com.xjb.kaoyancheckin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal sealed class UpdateCheck {
    data object NoManifest : UpdateCheck()
    data object Latest : UpdateCheck()
    data class Available(val manifest: UpdateManifest) : UpdateCheck()
}
internal interface UpdateBackend {
    suspend fun check(currentCode: Int): UpdateCheck
    suspend fun download(manifest: UpdateManifest, directory: File, progress: (Long, Long) -> Unit): File
}
internal object UpdateServices { var backendOverride: UpdateBackend? = null }

internal class UpdateClient(
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : UpdateBackend {
    private fun connection(url: String, readTimeout: Int = 20_000): HttpURLConnection {
        val conn = openConnection(url)
        conn.connectTimeout = 20_000
        conn.readTimeout = readTimeout
        conn.setRequestProperty("User-Agent", "KaoyanCheckin-Android/0.4")
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/json, application/octet-stream")
        conn.instanceFollowRedirects = true
        return conn
    }

    private fun response(conn: HttpURLConnection) {
        val status = conn.responseCode
        if (status != HttpURLConnection.HTTP_OK) throw UpdateException(UpdateFailure.HTTP, "HTTP $status")
        if (!conn.url.protocol.equals("https", true)) throw UpdateException(UpdateFailure.INVALID_DATA, "更新地址不是 HTTPS")
    }

    private fun metadata(url: String): String {
        val conn = connection(url)
        try {
            response(conn)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val text = StringBuilder()
                val chars = CharArray(4096)
                while (true) {
                    val n = reader.read(chars)
                    if (n < 0) break
                    text.append(chars, 0, n)
                    if (text.length > 1_048_576) throw UpdateException(UpdateFailure.INVALID_DATA, "更新信息过大")
                }
                text.toString()
            }
        } finally { conn.disconnect() }
    }

    override suspend fun check(currentCode: Int): UpdateCheck = withContext(Dispatchers.IO) {
        when (val asset = UpdateProtocol.releaseAsset(metadata(UpdateProtocol.API_URL))) {
            ReleaseAsset.Missing -> UpdateCheck.NoManifest
            is ReleaseAsset.Manifest -> {
                val manifest = UpdateProtocol.parseManifest(metadata(asset.url))
                if (UpdateProtocol.isNewer(manifest, currentCode)) UpdateCheck.Available(manifest) else UpdateCheck.Latest
            }
        }
    }

    override suspend fun download(manifest: UpdateManifest, directory: File, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException(UpdateFailure.STORAGE, "无法创建缓存目录")
        val file = File(directory, "update.apk")
        val partial = File(directory.parentFile ?: directory, "update-download.part")
        if ((file.exists() && !file.delete()) || (partial.exists() && !partial.delete()))
            throw UpdateException(UpdateFailure.STORAGE, "无法清理旧安装包")
        val conn = connection(manifest.apkUrl, 30_000)
        try {
            response(conn)
            val total = conn.contentLengthLong
            if (total > 512L * 1024 * 1024) throw UpdateException(UpdateFailure.STORAGE, "安装包过大")
            val output = try { partial.outputStream().buffered() } catch (e: IOException) {
                throw UpdateException(UpdateFailure.STORAGE, "无法创建安装包文件", e)
            }
            try { output.use { out ->
                val networkInput = try { conn.inputStream } catch (e: IOException) {
                    throw UpdateException(UpdateProtocol.classify(e), "无法读取安装包", e)
                }
                networkInput.buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var lastProgress = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = try { input.read(buffer) } catch (e: IOException) {
                            throw UpdateException(UpdateProtocol.classify(e), "下载中断", e)
                        }
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > 512L * 1024 * 1024) throw UpdateException(UpdateFailure.STORAGE, "安装包过大")
                        try { out.write(buffer, 0, count) } catch (e: IOException) {
                            throw UpdateException(UpdateFailure.STORAGE, "无法写入安装包", e)
                        }
                        val now = System.nanoTime()
                        if (now - lastProgress >= 250_000_000L) { progress(downloaded, total); lastProgress = now }
                    }
                    progress(downloaded, total)
                    if (downloaded == 0L) throw UpdateException(UpdateFailure.MISSING_APK, "下载的安装包为空")
                    if (total >= 0 && downloaded != total) throw UpdateException(UpdateFailure.NETWORK, "安装包下载不完整")
                }
                try { out.flush() } catch (e: IOException) { throw UpdateException(UpdateFailure.STORAGE, "无法保存安装包", e) }
            } } catch (e: IOException) { throw UpdateException(UpdateFailure.STORAGE, "无法关闭安装包文件", e) }
            val actual = try { UpdateProtocol.sha256(partial) } catch (e: IOException) {
                throw UpdateException(UpdateFailure.STORAGE, "无法读取安装包", e)
            }
            if (!UpdateProtocol.checksumMatches(manifest.sha256, actual)) throw UpdateException(UpdateFailure.CHECKSUM, "SHA-256 不匹配")
            if (!partial.renameTo(file)) throw UpdateException(UpdateFailure.STORAGE, "无法保存安装包")
            file
        } finally {
            conn.disconnect()
            partial.delete()
        }
    }
}
