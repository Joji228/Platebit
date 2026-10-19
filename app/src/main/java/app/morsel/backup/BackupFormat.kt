package app.morsel.backup

import app.morsel.data.Backup
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** How a backup is stored in Drive: gzipped JSON, the same JSON as Settings → Export backup. */
object BackupCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(backup: Backup): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.encodeToString(Backup.serializer(), backup).toByteArray()) }
        return out.toByteArray()
    }

    /** Reads gzipped or plain JSON, so a file exported by hand works too. */
    fun decode(bytes: ByteArray): Backup {
        val gzipped = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val text = if (gzipped) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        return json.decodeFromString(Backup.serializer(), text.decodeToString())
    }

    /** Changes only when the data does, so an unchanged phone never uploads again. */
    fun fingerprint(backup: Backup): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(json.encodeToString(Backup.serializer(), backup).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** A backup file in the user's hidden Platebit folder in Drive. */
data class CloudFile(
    val id: String,
    val name: String,
    /** Random tag written with every upload; tells this phone whether someone else wrote since. */
    val rev: String?,
    val deviceId: String?,
    val deviceName: String?,
    val savedAt: Long?,
    val meals: Int?,
) {
    val isSnapshot get() = name.startsWith(SNAPSHOT_PREFIX)

    companion object {
        const val LATEST = "backup.json.gz"
        const val SNAPSHOT_PREFIX = "daily-"

        /** One copy per day is kept for this many days, in case a bad change gets backed up. */
        const val SNAPSHOT_DAYS = 7

        fun snapshotName(day: java.time.LocalDate) = "$SNAPSHOT_PREFIX$day.json.gz"
    }
}

sealed interface UploadDecision {
    /** Nothing in the cloud yet. */
    data object Create : UploadDecision

    data class Overwrite(val file: CloudFile) : UploadDecision

    /** The cloud already holds exactly this phone's data. */
    data object Unchanged : UploadDecision

    /** The cloud holds data this phone hasn't seen: ask the user instead of overwriting it. */
    data class Conflict(val file: CloudFile) : UploadDecision
}

/**
 * The rule that keeps a backup from ever being overwritten by mistake. A phone may replace the
 * cloud copy only if that copy is the one it last wrote or restored ([lastRev]), or if this same
 * phone wrote it (its earlier upload went through but the reply was lost). A new phone, or an
 * old phone waking up after another took over, has to ask first. A phone with nothing logged
 * never replaces a backup that has meals in it.
 */
fun decideUpload(
    cloud: CloudFile?,
    lastRev: String?,
    deviceId: String,
    localEmpty: Boolean,
    fingerprint: String,
    lastFingerprint: String?,
): UploadDecision {
    if (cloud == null) return UploadDecision.Create
    if (localEmpty && (cloud.meals ?: 0) > 0) return UploadDecision.Conflict(cloud)
    val ours = (cloud.rev != null && cloud.rev == lastRev) || cloud.deviceId == deviceId
    if (!ours) return UploadDecision.Conflict(cloud)
    return if (cloud.rev == lastRev && fingerprint == lastFingerprint) UploadDecision.Unchanged else UploadDecision.Overwrite(cloud)
}

/** Daily copies older than the newest [CloudFile.SNAPSHOT_DAYS]: the ones to delete. */
fun staleSnapshots(files: List<CloudFile>): List<CloudFile> =
    files.filter { it.isSnapshot }.sortedByDescending { it.name }.drop(CloudFile.SNAPSHOT_DAYS)
