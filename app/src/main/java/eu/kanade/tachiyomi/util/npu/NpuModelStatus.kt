package eu.kanade.tachiyomi.util.npu

import java.io.File

sealed interface NpuModelStatus {
    data object CalibrationRequired : NpuModelStatus
    data class Downloading(val downloaded: Long, val total: Long) : NpuModelStatus
    data class Calibrating(val stage: Int, val completed: Int, val total: Int) : NpuModelStatus
    data object Preparing : NpuModelStatus
    data class Ready(val file: File) : NpuModelStatus
    data class Failed(val message: String) : NpuModelStatus
}
