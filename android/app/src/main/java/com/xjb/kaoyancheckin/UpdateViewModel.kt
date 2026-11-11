package com.xjb.kaoyancheckin

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

internal class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val backend = UpdateServices.backendOverride ?: UpdateClient()
    private val preferences = app.getSharedPreferences("update_check", 0)
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var awaitingPermission = false
    private var verifiedFile: File? = null
    var checking by mutableStateOf(false); private set
    var downloading by mutableStateOf(false); private set
    var downloaded by mutableStateOf(0L); private set
    var total by mutableStateOf(-1L); private set
    var available by mutableStateOf<UpdateManifest?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var dialogVisible by mutableStateOf(false); private set
    var permissionNeeded by mutableStateOf(false); private set

    fun onResume() {
        if (awaitingPermission) {
            awaitingPermission = false
            if (getApplication<Application>().packageManager.canRequestPackageInstalls()) install()
            else permissionNeeded = true
        }
        if (preferences.getString("last_auto_check", null) != CheckinCore.today() && checkJob?.isActive != true) {
            preferences.edit().putString("last_auto_check", CheckinCore.today()).apply()
            check(silent = true)
        }
    }

    fun check(silent: Boolean = false) {
        if (checkJob?.isActive == true || downloading) return
        checkJob = viewModelScope.launch {
            checking = true
            try {
                when (val result = backend.check(BuildConfig.VERSION_CODE)) {
                    is UpdateCheck.Available -> {
                        if (available != result.manifest) verifiedFile = null
                        available = result.manifest
                        message = null
                        permissionNeeded = false
                        dialogVisible = true
                    }
                    UpdateCheck.Latest -> if (!silent) { available = null; verifiedFile = null; permissionNeeded = false; message = "已是最新版本。"; dialogVisible = true }
                    UpdateCheck.NoManifest -> if (!silent) { available = null; verifiedFile = null; permissionNeeded = false; message = "当前 Release 没有自动更新信息。可到 Release 页面手动下载。"; dialogVisible = true }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!silent) { available = null; verifiedFile = null; permissionNeeded = false; message = UpdateProtocol.message(UpdateProtocol.classify(e)); dialogVisible = true }
            } finally { checking = false }
        }
    }

    fun update() {
        val manifest = available ?: return
        if (downloading) return
        if (verifiedFile?.isFile == true) { install(); return }
        downloadJob = viewModelScope.launch {
            downloading = true; downloaded = 0; total = -1; message = null
            try {
                val file = backend.download(manifest, File(getApplication<Application>().cacheDir, "updates")) { bytes, size ->
                    viewModelScope.launch { downloaded = bytes; total = size }
                }
                verifiedFile = file
                install()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = UpdateProtocol.message(UpdateProtocol.classify(e)) }
            finally { downloading = false }
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); message = "已取消下载，可稍后重试或到 Release 页面手动下载。" }
    fun dismiss() { if (!downloading) dialogVisible = false }

    fun openPermissionSettings() {
        try {
            val context = getApplication<Application>()
            awaitingPermission = true
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            awaitingPermission = false
            message = UpdateProtocol.message(UpdateFailure.INSTALL)
        }
    }

    private fun install() {
        val context = getApplication<Application>()
        val file = verifiedFile ?: return
        try {
            if (!context.packageManager.canRequestPackageInstalls()) { permissionNeeded = true; return }
            permissionNeeded = false
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("APK", uri)
            context.startActivity(intent)
        } catch (_: Exception) { message = UpdateProtocol.message(UpdateFailure.INSTALL) }
    }
}
