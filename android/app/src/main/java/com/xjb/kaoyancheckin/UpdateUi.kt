package com.xjb.kaoyancheckin

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjb.kaoyancheckin.ui.theme.webColors

@Composable
internal fun UpdateSettings(model: UpdateViewModel) {
    Section("应用更新") {
        Tip("检查更新时会访问 GitHub；学习数据仍只保存在本机。")
        Spacer(Modifier.height(8.dp))
        WebButton(if (model.checking) "正在检查…" else "检查更新", { model.check() }, Modifier.fillMaxWidth(),
            enabled = !model.checking && !model.downloading, big = true)
    }
}

@Composable
internal fun UpdateDialog(model: UpdateViewModel, notify: (String) -> Unit) {
    if (!model.dialogVisible) return
    val context = LocalContext.current
    fun openRelease() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateProtocol.RELEASE_URL))) }
        catch (_: Exception) {
            model.dismiss()
            notify("无法打开浏览器，请手动访问 ${UpdateProtocol.RELEASE_URL}")
        }
    }
    val update = model.available
    AlertDialog(
        onDismissRequest = { model.dismiss() },
        title = { Text(if (update != null) "发现新版本 ${update.versionName}" else "检查更新") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                update?.let { Text(it.notes, color = webColors.text) }
                if (model.downloading) {
                    val fraction = if (model.total > 0) (model.downloaded.toFloat() / model.total).coerceIn(0f, 1f) else null
                    if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(if (fraction != null) "下载中 ${(fraction * 100).toInt()}%" else "下载中 ${model.downloaded / 1024} KB")
                }
                if (model.permissionNeeded) Text("请允许此应用安装未知来源应用，返回后将打开系统安装器。")
                model.message?.let { Text(it) }
            }
        },
        confirmButton = {
            when {
                model.downloading -> TextButton(onClick = { model.cancelDownload() }) { Text("取消下载") }
                model.permissionNeeded -> TextButton(onClick = { model.openPermissionSettings() }) { Text("前往设置") }
                update != null -> TextButton(onClick = { model.update() }) { Text("更新") }
                else -> TextButton(onClick = { model.dismiss() }) { Text("知道了") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { openRelease() }) { Text("手动下载") }
                if (!model.downloading && update != null) TextButton(onClick = { model.dismiss() }) { Text("稍后") }
            }
        },
    )
}
