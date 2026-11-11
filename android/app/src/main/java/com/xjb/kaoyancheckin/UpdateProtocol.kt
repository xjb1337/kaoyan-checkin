package com.xjb.kaoyancheckin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest

internal data class UpdateManifest(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String, val notes: String)
internal sealed class ReleaseAsset {
    data class Manifest(val url: String) : ReleaseAsset()
    data object Missing : ReleaseAsset()
}
internal enum class UpdateFailure { NETWORK, TIMEOUT, HTTP, INVALID_DATA, MISSING_APK, CHECKSUM, STORAGE, INSTALL }
internal class UpdateException(val failure: UpdateFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

internal object UpdateProtocol {
    const val RELEASE_URL = "https://github.com/xjb1337/kaoyan-checkin/releases/latest"
    const val API_URL = "https://api.github.com/repos/xjb1337/kaoyan-checkin/releases/latest"
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }

    private fun obj(raw: String): JsonObject = try {
        json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException()
    } catch (e: Exception) { throw UpdateException(UpdateFailure.INVALID_DATA, "更新信息不是有效的 JSON", e) }

    private fun string(obj: JsonObject, name: String): String? =
        (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun https(value: String?): Boolean = try {
        val uri = URI(value ?: "")
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) { false }

    fun releaseAsset(raw: String): ReleaseAsset {
        val root = obj(raw)
        val assets = root["assets"] as? JsonArray ?: throw UpdateException(UpdateFailure.INVALID_DATA, "Release 资产列表格式错误")
        val entries = assets.map { element ->
            val entry = element as? JsonObject ?: throw UpdateException(UpdateFailure.INVALID_DATA, "Release 资产格式错误")
            if (string(entry, "name") == null) throw UpdateException(UpdateFailure.INVALID_DATA, "Release 资产缺少名称")
            entry
        }
        val asset = entries.firstOrNull { string(it, "name") == "update.json" } ?: return ReleaseAsset.Missing
        val url = string(asset, "browser_download_url")
        if (!https(url)) throw UpdateException(UpdateFailure.INVALID_DATA, "update.json 下载地址无效")
        return ReleaseAsset.Manifest(url!!)
    }

    fun parseManifest(raw: String): UpdateManifest {
        val root = obj(raw)
        val codePrimitive = root["versionCode"] as? JsonPrimitive
        val code = codePrimitive?.takeUnless { it.isString }?.intOrNull
        val name = string(root, "versionName")
        val apk = string(root, "apkUrl")
        val hash = string(root, "sha256")
        val notes = string(root, "notes")
        if (code == null || code <= 0 || name == null || hash == null || notes == null)
            throw UpdateException(UpdateFailure.INVALID_DATA, "update.json 缺少必要字段或字段格式错误")
        if (!https(apk)) throw UpdateException(UpdateFailure.MISSING_APK, "更新信息没有有效的 APK 地址")
        if (normalizeSha256(hash) == null) throw UpdateException(UpdateFailure.INVALID_DATA, "SHA-256 格式错误")
        return UpdateManifest(code, name, apk!!, hash, notes)
    }

    fun isNewer(update: UpdateManifest, currentVersionCode: Int): Boolean = update.versionCode > currentVersionCode

    fun normalizeSha256(value: String): String? {
        if (!value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '-' || it.isWhitespace() }) return null
        val normalized = value.filterNot { it == ':' || it == '-' || it.isWhitespace() }.lowercase()
        return normalized.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun checksumMatches(expected: String, actual: String): Boolean =
        normalizeSha256(expected)?.let { it == normalizeSha256(actual) } ?: false

    fun classify(error: Throwable): UpdateFailure = when (error) {
        is UpdateException -> error.failure
        is SocketTimeoutException -> UpdateFailure.TIMEOUT
        is UnknownHostException, is ConnectException -> UpdateFailure.NETWORK
        is IOException -> UpdateFailure.NETWORK
        else -> UpdateFailure.INVALID_DATA
    }

    fun message(failure: UpdateFailure): String = when (failure) {
        UpdateFailure.NETWORK -> "网络连接失败，请检查网络。"
        UpdateFailure.TIMEOUT -> "连接 GitHub 超时，请稍后重试。"
        UpdateFailure.HTTP -> "GitHub 返回错误，暂时无法获取更新。"
        UpdateFailure.INVALID_DATA -> "更新信息无效或已损坏。"
        UpdateFailure.MISSING_APK -> "更新信息中没有可用的 APK。"
        UpdateFailure.CHECKSUM -> "安装包 SHA-256 校验失败，未启动安装。"
        UpdateFailure.STORAGE -> "无法保存或读取安装包，请检查手机存储空间。"
        UpdateFailure.INSTALL -> "无法打开系统安装器，请检查系统设置。"
    } + "也可以到 Release 页面手动下载。"
}
