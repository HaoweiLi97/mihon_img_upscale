package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class BackupCloudSyncState(
    @ProtoNumber(1) val targetId: String,
    @ProtoNumber(2) val chapterUrls: List<String> = emptyList(),
    @ProtoNumber(3) val metaInfoHash: String? = null,
    @ProtoNumber(4) val mangaFolders: List<String> = emptyList(),
    @ProtoNumber(5) val chapterFiles: List<BackupCloudChapterFile> = emptyList(),
)

@Serializable
data class BackupCloudChapterFile(
    @ProtoNumber(1) val chapterUrl: String,
    @ProtoNumber(2) val directory: String,
    @ProtoNumber(3) val fileName: String,
    @ProtoNumber(4) val size: Long? = null,
)

internal val cloudSyncTargetPattern = Regex("[a-f0-9]{64}")
