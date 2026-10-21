package eu.kanade.tachiyomi.util.npu

import java.io.File

sealed interface NpuModelStatus {
    data object Preparing : NpuModelStatus
    data class Ready(val file: File) : NpuModelStatus
    data class Failed(val message: String) : NpuModelStatus
}
