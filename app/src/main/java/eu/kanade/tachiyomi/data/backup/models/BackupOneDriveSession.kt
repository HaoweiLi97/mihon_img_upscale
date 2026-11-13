package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
class BackupOneDriveSession(
    @ProtoNumber(1) val driveId: String,
    @ProtoNumber(2) val clientId: String,
    @ProtoNumber(3) val refreshToken: String,
    @ProtoNumber(4) val destination: String? = null,
)
