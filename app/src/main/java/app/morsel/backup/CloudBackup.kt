package app.morsel.backup

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.morsel.MorselApp
import app.morsel.data.Backup
import app.morsel.data.MorselRepository
import app.morsel.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.backupStore by preferencesDataStore("backup_state")

/** Another phone's backup that this phone hasn't seen, shown so the user can choose what to keep. */
@Serializable
data class CloudInfo(val deviceName: String? = null, val savedAt: Long? = null, val meals: Int? = null)

sealed interface BackupProblem {
    /** Google wants the user to sign in again (access removed, password changed, ...). */
    data object Reconnect : BackupProblem

    /** The cloud holds data this phone hasn't seen. Nothing uploads until the user picks a side. */
    data class Conflict(val cloud: CloudInfo) : BackupProblem

    /** The last try failed (no internet, Google down); it is retried by itself. */
    data class Failed(val message: String) : BackupProblem
}

data class BackupStatus(
    val account: GoogleAccount? = null,
    val lastBackupAt: Long? = null,
    val problem: BackupProblem? = null,
    val running: Boolean = false,
    /** Changes are waiting for the next upload (normally within a minute, or once online). */
    val pending: Boolean = false,
) {
    val signedIn get() = account != null
}

/** One file the user can restore from: the latest backup or one of the daily copies. */
data class RestorePoint(val fileId: String, val latest: Boolean, val info: CloudInfo)

/**
 * Automatic backup to the user's own Google Drive, in the hidden app folder only Platebit can
 * see. Logs, the profile and settings are uploaded about 30 seconds after any change and once
 * a day; meal photos and API keys stay on the phone. Built for one phone at a time: a phone
 * never overwrites a backup it hasn't seen (see [decideUpload]); the user chooses instead.
 */
class CloudBackup(
    private val context: Context,
    private val repository: MorselRepository,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
) {
    private val store = context.applicationContext.backupStore
    private val auth = GoogleAuth(context)
    private val drive = DriveClient()
    private val work = WorkManager.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val running = MutableStateFlow(false)

    private val accountKey = stringPreferencesKey("account")
    private val deviceIdKey = stringPreferencesKey("device_id")
    private val lastRevKey = stringPreferencesKey("last_rev")
    private val lastFingerprintKey = stringPreferencesKey("last_fingerprint")
    private val lastBackupKey = longPreferencesKey("last_backup_at")
    private val problemKey = stringPreferencesKey("problem")
    private val problemDetailKey = stringPreferencesKey("problem_detail")

    private fun problemOf(prefs: Preferences): BackupProblem? = when (prefs[problemKey]) {
        "reconnect" -> BackupProblem.Reconnect
        "conflict" -> BackupProblem.Conflict(prefs[problemDetailKey]?.let { runCatching { json.decodeFromString<CloudInfo>(it) }.getOrNull() } ?: CloudInfo())
        "failed" -> BackupProblem.Failed(prefs[problemDetailKey].orEmpty())
        else -> null
    }

    private val pending: Flow<Boolean> = work.getWorkInfosForUniqueWorkFlow(SOON).map { infos ->
        infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    }

    val status: StateFlow<BackupStatus> = combine(store.data, running, pending) { prefs, isRunning, isPending ->
        BackupStatus(
            account = prefs[accountKey]?.let { runCatching { json.decodeFromString<GoogleAccount>(it) }.getOrNull() },
            lastBackupAt = prefs[lastBackupKey],
            problem = problemOf(prefs),
            running = isRunning,
            pending = isPending,
        )
    }.stateIn(scope, SharingStarted.Eagerly, BackupStatus())

    private suspend fun signedInEmail(): String? = store.data.first()[accountKey]?.let {
        runCatching { json.decodeFromString<GoogleAccount>(it) }.getOrNull()?.email
    }

    private suspend fun deviceId(): String {
        store.data.first()[deviceIdKey]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { it[deviceIdKey] = id }
        return id
    }

    private suspend fun setProblem(problem: BackupProblem?) {
        store.edit { prefs ->
            when (problem) {
                null -> {
                    prefs.remove(problemKey)
                    prefs.remove(problemDetailKey)
                }
                BackupProblem.Reconnect -> {
                    prefs[problemKey] = "reconnect"
                    prefs.remove(problemDetailKey)
                }
                is BackupProblem.Conflict -> {
                    prefs[problemKey] = "conflict"
                    prefs[problemDetailKey] = json.encodeToString(CloudInfo.serializer(), problem.cloud)
                }
                is BackupProblem.Failed -> {
                    prefs[problemKey] = "failed"
                    prefs[problemDetailKey] = problem.message
                }
            }
        }
    }

    // ---- Signing in -------------------------------------------------------------------------

    sealed interface SignIn {
        /** Show Google's account picker / permission screen, then call [finishSignIn]. */
        data class NeedsUser(val intent: android.app.PendingIntent) : SignIn

        data class Done(val result: Connected) : SignIn
    }

    sealed interface Connected {
        /** No backup in this account yet; this phone's data is being uploaded now. */
        data object Fresh : Connected

        /** This phone already owns the backup in the account. */
        data object Linked : Connected

        /** A backup from another phone (or an earlier install) is there: restore it or replace it. */
        data class Found(val points: List<RestorePoint>) : Connected
    }

    /** Step one of signing in, or of reconnecting after Google asked to sign in again. */
    suspend fun startSignIn(): SignIn = when (val result = auth.authorize(signedInEmail())) {
        is GoogleAuth.Result.NeedsUser -> SignIn.NeedsUser(result.intent)
        is GoogleAuth.Result.Token -> SignIn.Done(connect(result.accessToken))
    }

    /** Step two, with what Google's screen returned. */
    suspend fun finishSignIn(data: android.content.Intent?): Connected = connect(auth.tokenFrom(data))

    private suspend fun connect(token: String): Connected = mutex.withLock {
        val account = drive.account(token)
        val previous = signedInEmail()
        store.edit { prefs ->
            if (previous != null && previous != account.email) {
                // A different account: nothing this phone knew about the old one applies.
                prefs.remove(lastRevKey)
                prefs.remove(lastFingerprintKey)
                prefs.remove(lastBackupKey)
            }
            prefs[accountKey] = json.encodeToString(GoogleAccount.serializer(), account)
        }
        setProblem(null)
        val files = drive.list(token)
        val latest = files.firstOrNull { it.name == CloudFile.LATEST }
        val state = store.data.first()
        val result = when {
            latest == null -> Connected.Fresh
            (latest.rev != null && latest.rev == state[lastRevKey]) || latest.deviceId == deviceId() -> Connected.Linked
            else -> {
                setProblem(BackupProblem.Conflict(latest.info()))
                Connected.Found(restorePoints(files))
            }
        }
        ensureDaily()
        if (result != Connected.Linked || state[lastBackupKey] == null) scheduleSoon(delaySeconds = 0)
        result
    }

    suspend fun signOut() {
        val email = signedInEmail()
        work.cancelUniqueWork(SOON)
        work.cancelUniqueWork(DAILY)
        // The phone also forgets its id, so signing in again later always asks before touching the backup.
        store.edit { it.clear() }
        email?.let { auth.revoke(it) }
    }

    /**
     * For "Erase everything": deletes every Platebit file in the user's Drive (the latest backup
     * and the daily copies), checks that none is left, then signs out. Throws when the backup
     * couldn't be deleted, so the phone isn't erased while its backup lives on. Does nothing
     * when no account is signed in.
     */
    suspend fun deleteEverything() {
        val email = signedInEmail() ?: return
        // No upload may start (and recreate the file) while the backup is being deleted.
        work.cancelUniqueWork(SOON)
        work.cancelUniqueWork(DAILY)
        mutex.withLock {
            try {
                withToken { token ->
                    drive.list(token).forEach { file -> runCatching { drive.delete(token, file.id) } }
                    if (drive.list(token).isNotEmpty()) throw BackupException("Some backup files are still in your Google Drive")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsReconnectException) {
                ensureDaily()
                throw BackupException("Google needs you to sign in again first (Settings → Google Drive backup → Sign in again)")
            } catch (e: Exception) {
                ensureDaily()
                throw e
            }
            store.edit { it.clear() }
        }
        auth.revoke(email)
    }

    // ---- Restoring --------------------------------------------------------------------------

    suspend fun restorePoints(): List<RestorePoint> = withToken { token -> restorePoints(drive.list(token)) }

    private fun restorePoints(files: List<CloudFile>): List<RestorePoint> {
        val latest = files.filter { it.name == CloudFile.LATEST }.map { RestorePoint(it.id, latest = true, it.info()) }
        val daily = files.filter { it.isSnapshot }.sortedByDescending { it.name }.map { RestorePoint(it.id, latest = false, it.info()) }
        return latest + daily
    }

    /** Replaces everything on this phone with [point], and returns what was restored. */
    suspend fun restore(point: RestorePoint): Backup = mutex.withLock {
        withToken { token ->
            val backup = BackupCodec.decode(drive.download(token, point.fileId))
            repository.restore(backup)
            backup.profile?.let { settings.saveProfile(it) }
            backup.settings?.let { settings.restore(it) }
            val latest = drive.list(token).firstOrNull { it.name == CloudFile.LATEST }
            val now = repository.export(settings.profile.first(), settings.backup())
            store.edit { prefs ->
                // In step with the latest backup again, so this phone may write to it from now on.
                // After restoring an older copy, the next upload makes that copy the latest.
                latest?.rev?.let { prefs[lastRevKey] = it } ?: prefs.remove(lastRevKey)
                if (point.latest) prefs[lastFingerprintKey] = BackupCodec.fingerprint(now) else prefs.remove(lastFingerprintKey)
                prefs[lastBackupKey] = System.currentTimeMillis()
            }
            setProblem(null)
            if (!point.latest) scheduleSoon(delaySeconds = 0)
            backup
        }
    }

    /** Resolving a conflict the other way: this phone's data replaces what's in Drive. */
    suspend fun replaceCloudWithThisPhone() = backUp(force = true)

    // ---- Backing up -------------------------------------------------------------------------

    enum class Outcome { DONE, SKIPPED, RETRY }

    /** Called by the worker and by "Back up now". Never overwrites a backup it hasn't seen unless [force]. */
    suspend fun backUp(force: Boolean = false): Outcome = mutex.withLock {
        val state = store.data.first()
        if (state[accountKey] == null) return Outcome.SKIPPED
        if (!force && problemOf(state) is BackupProblem.Conflict) return Outcome.SKIPPED
        running.value = true
        try {
            withToken { token -> upload(token, force, state) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NeedsReconnectException) {
            setProblem(BackupProblem.Reconnect)
            Outcome.SKIPPED
        } catch (e: IOException) {
            setProblem(BackupProblem.Failed(e.message ?: "No connection"))
            Outcome.RETRY
        } catch (e: Exception) {
            setProblem(BackupProblem.Failed(e.message ?: e.javaClass.simpleName))
            Outcome.RETRY
        } finally {
            running.value = false
        }
    }

    private suspend fun upload(token: String, force: Boolean, state: Preferences): Outcome {
        val files = drive.list(token)
        val latest = files.firstOrNull { it.name == CloudFile.LATEST }
        val backup = repository.export(settings.profile.first(), settings.backup())
        val fingerprint = BackupCodec.fingerprint(backup)
        val decision = if (force) {
            latest?.let { UploadDecision.Overwrite(it) } ?: UploadDecision.Create
        } else {
            decideUpload(latest, state[lastRevKey], deviceId(), backup.isEmpty, fingerprint, state[lastFingerprintKey])
        }
        when (decision) {
            is UploadDecision.Conflict -> {
                setProblem(BackupProblem.Conflict(decision.file.info()))
                return Outcome.SKIPPED
            }
            UploadDecision.Unchanged -> Unit
            UploadDecision.Create, is UploadDecision.Overwrite -> {
                val rev = UUID.randomUUID().toString()
                val bytes = BackupCodec.encode(backup)
                val properties = mapOf(
                    DriveClient.REV to rev,
                    DriveClient.DEVICE_ID to deviceId(),
                    DriveClient.DEVICE_NAME to deviceName(),
                    DriveClient.SAVED_AT to System.currentTimeMillis().toString(),
                    DriveClient.MEALS to backup.meals.size.toString(),
                )
                drive.upload(token, latest?.id, CloudFile.LATEST, bytes, properties)
                store.edit { prefs ->
                    prefs[lastRevKey] = rev
                    prefs[lastFingerprintKey] = fingerprint
                }
                // The first backup of each day is also kept as that day's copy, for a week.
                val today = CloudFile.snapshotName(LocalDate.now())
                if (files.none { it.name == today }) {
                    runCatching {
                        val copy = drive.upload(token, null, today, bytes, properties)
                        staleSnapshots(files + copy).forEach { drive.delete(token, it.id) }
                    }
                }
            }
        }
        store.edit { it[lastBackupKey] = System.currentTimeMillis() }
        setProblem(null)
        return Outcome.DONE
    }

    /** Runs [block] with a valid token, fetching a fresh one once if Google rejects the cached one. */
    private suspend fun <T> withToken(block: suspend (String) -> T): T {
        val email = signedInEmail() ?: throw NeedsReconnectException()
        suspend fun token(): String = when (val result = auth.authorize(email)) {
            is GoogleAuth.Result.NeedsUser -> throw NeedsReconnectException()
            is GoogleAuth.Result.Token -> result.accessToken
        }
        val first = token()
        return try {
            block(first)
        } catch (e: TokenExpiredException) {
            auth.forget(first)
            block(token())
        }
    }

    private fun CloudFile.info() = CloudInfo(deviceName = deviceName, savedAt = savedAt, meals = meals)

    /** "Galaxy S25" where the phone has a name set, otherwise the model. */
    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    // ---- Scheduling -------------------------------------------------------------------------

    /**
     * Any change restarts a short countdown, so a burst of edits becomes one upload. WorkManager
     * waits for a connection and runs it even if the app was closed meanwhile.
     */
    fun scheduleSoon(delaySeconds: Long = 30) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        work.enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
    }

    /** The once-a-day safety net. An unchanged phone only checks and uploads nothing. */
    fun ensureDaily() {
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).setConstraints(online).build()
        work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Keeps the daily safety net in place for a signed-in phone (WorkManager drops it if the app is force-stopped). */
    suspend fun onAppStart() {
        if (signedInEmail() != null) ensureDaily()
    }

    /** Something worth backing up changed on this phone. */
    fun onDataChanged() {
        if (status.value.signedIn) scheduleSoon()
    }

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private class NeedsReconnectException : Exception("Sign in to Google again")

    companion object {
        /**
         * Switched off for now: Google only accepts sign-in from an app registered in a Google Cloud
         * project (package name + signing key), which isn't set up yet. Everything else is built and
         * tested; set this to true once the project exists to turn the Drive backup on.
         */
        const val AVAILABLE = false

        private const val SOON = "backup-soon"
        private const val DAILY = "backup-daily"
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as MorselApp).container.backup
        return when (backup.backUp()) {
            CloudBackup.Outcome.RETRY -> if (runAttemptCount < 6) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}
