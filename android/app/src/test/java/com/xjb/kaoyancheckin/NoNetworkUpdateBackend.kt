package com.xjb.kaoyancheckin

import java.io.File

internal object NoNetworkUpdateBackend : UpdateBackend {
    override suspend fun check(currentCode: Int): UpdateCheck = UpdateCheck.NoManifest
    override suspend fun download(manifest: UpdateManifest, directory: File, progress: (Long, Long) -> Unit): File = error("Not used in UI tests")
}
