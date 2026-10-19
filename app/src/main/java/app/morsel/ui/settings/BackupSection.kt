package app.morsel.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsel.backup.BackupProblem
import app.morsel.backup.BackupStatus
import app.morsel.backup.CloudBackup
import app.morsel.backup.CloudInfo
import app.morsel.backup.RestorePoint
import app.morsel.ui.components.GroupedList
import app.morsel.ui.components.ListRow
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Starts Google sign-in from a screen; [busy] is true until it finishes. */
class GoogleSignIn(val start: () -> Unit, val busy: Boolean)

/**
 * Google's account picker and permission screen. [onConnected] gets what the account holds;
 * [onError] gets a readable reason, or nothing when the user just backed out.
 */
@Composable
fun rememberGoogleSignIn(
    backup: CloudBackup,
    onConnected: (CloudBackup.Connected) -> Unit,
    onError: (String) -> Unit,
): GoogleSignIn {
    val scope = rememberCoroutineScope()
    val connected by rememberUpdatedState(onConnected)
    val failed by rememberUpdatedState(onError)
    var working by remember { mutableStateOf(false) }
    var awaitingGoogle by remember { mutableStateOf(false) }

    suspend fun attempt(block: suspend () -> CloudBackup.Connected?) {
        working = true
        try {
            block()?.let { connected(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            describeSignInError(e)?.let { failed(it) }
        } finally {
            working = false
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        awaitingGoogle = false
        // Google returns its error (wrong app setup, no network…) with a non-OK result, so read the answer either way;
        // only a plain back-out with no data is ignored, and a cancel still shows nothing.
        if (result.resultCode == Activity.RESULT_OK || result.data != null) scope.launch { attempt { backup.finishSignIn(result.data) } }
    }

    val busy = working || awaitingGoogle
    return GoogleSignIn(
        start = {
            if (!busy) {
                scope.launch {
                    attempt {
                        when (val step = backup.startSignIn()) {
                            is CloudBackup.SignIn.Done -> step.result
                            is CloudBackup.SignIn.NeedsUser -> {
                                awaitingGoogle = true
                                launcher.launch(IntentSenderRequest.Builder(step.intent).build())
                                null
                            }
                        }
                    }
                }
            }
        },
        busy = busy,
    )
}

/** Null when there's nothing to tell (the user cancelled). */
internal fun describeSignInError(e: Throwable): String? = when {
    e is ApiException && e.statusCode == CommonStatusCodes.CANCELED -> null
    e is ApiException && (
        e.statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
            // Play services reports an unregistered app as an internal error (8) with this status in the message.
            (e.statusCode == CommonStatusCodes.INTERNAL_ERROR && e.message.orEmpty().contains("UNREGISTERED"))
    ) ->
        "Google sign-in isn't set up for this copy of Platebit. Its Google Cloud project needs an Android client " +
            "with this app's package name and signing fingerprint."
    e is ApiException && e.statusCode == CommonStatusCodes.NETWORK_ERROR -> "No internet connection. Try again when you're online."
    e is ApiException && e.statusCode == CommonStatusCodes.INTERNAL_ERROR ->
        "Google sign-in failed. If this keeps happening, this copy of Platebit may not be registered with Google yet."
    e is ApiException -> "Google sign-in failed (code ${e.statusCode})."
    e is IOException -> e.message ?: "No internet connection."
    else -> e.message ?: "Google sign-in failed."
}

/** The Backup group in Settings, with its dialogs. */
@Composable
fun BackupSection(backup: CloudBackup, onMessage: (String) -> Unit) {
    val status by backup.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf<List<RestorePoint>?>(null) }
    /** True when the choice comes from a conflict, so "keep this phone's data" is offered too. */
    var conflictChoice by remember { mutableStateOf(false) }
    var signOutDialog by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun showPoints(conflict: Boolean) {
        scope.launch {
            loading = true
            runCatching { backup.restorePoints() }
                .onSuccess { points ->
                    if (points.isEmpty()) onMessage("There's no backup in this Google account yet.") else {
                        conflictChoice = conflict
                        choosing = points
                    }
                }
                .onFailure { onMessage(describeSignInError(it) ?: "Couldn't reach Google Drive.") }
            loading = false
        }
    }

    val signIn = rememberGoogleSignIn(
        backup,
        onConnected = { result ->
            when (result) {
                CloudBackup.Connected.Fresh -> onMessage("Backup is on. Your logs are being saved to your Google Drive now.")
                CloudBackup.Connected.Linked -> Unit
                is CloudBackup.Connected.Found -> {
                    conflictChoice = true
                    choosing = result.points
                }
            }
        },
        onError = onMessage,
    )

    // Re-render every 30 s so "2 min ago" stays true while the screen is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val account = status.account
    val rows: List<@Composable () -> Unit> = if (account == null) {
        listOf(
            {
                ListRow(
                    if (signIn.busy) "Signing in…" else "Back up to Google Drive",
                    subtitle = "Sign in with Google and your logs, weights, profile and settings back up by themselves. " +
                        "They go to your own Drive. Photos and API keys stay on this phone.",
                    icon = Icons.Rounded.CloudUpload,
                    onClick = signIn.start,
                )
            },
        )
    } else {
        listOf(
            {
                ListRow(
                    account.name ?: account.email,
                    subtitle = if (account.name != null) account.email else null,
                    icon = Icons.Rounded.AccountCircle,
                )
            },
            {
                StatusRow(status, now, signIn, onResolve = { showPoints(conflict = true) }, onBackUpNow = { scope.launch { backup.backUp() } })
            },
            {
                ListRow(
                    if (loading) "Looking for backups…" else "Restore from Google Drive",
                    subtitle = "Replaces the data on this phone with a backup",
                    icon = Icons.Rounded.Restore,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { if (!loading) showPoints(conflict = false) },
                )
            },
            {
                ListRow(
                    "Sign out",
                    subtitle = "Stops backing up. Your backup stays in your Google Drive",
                    icon = Icons.AutoMirrored.Rounded.Logout,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { signOutDialog = true },
                )
            },
        )
    }
    GroupedList(rows = rows)

    choosing?.let { points ->
        ChooseBackupDialog(
            points = points,
            conflict = conflictChoice,
            onRestore = { point ->
                choosing = null
                scope.launch {
                    loading = true
                    runCatching { backup.restore(point) }
                        .onSuccess { restored ->
                            val meals = restored.meals.size
                            onMessage("Restored ${if (meals == 1) "1 meal" else "$meals meals"} from Google Drive.")
                        }
                        .onFailure { onMessage("Restore failed: ${it.message}") }
                    loading = false
                }
            },
            onKeepPhone = {
                choosing = null
                scope.launch {
                    if (backup.replaceCloudWithThisPhone() == CloudBackup.Outcome.DONE) {
                        onMessage("Done. This phone's data is now the backup.")
                    } else {
                        onMessage("Couldn't replace the backup. Check your connection and try again.")
                    }
                }
            },
            onDismiss = { choosing = null },
        )
    }

    if (signOutDialog) {
        AlertDialog(
            onDismissRequest = { signOutDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Sign out of Google?") },
            text = {
                Text(
                    "Platebit stops backing up this phone. Everything on the phone stays, and your backup stays in your " +
                        "Google Drive, so you can sign in again any time.\n\nTo delete the backup itself: Google Drive → " +
                        "Settings → Manage apps → Platebit → Delete hidden app data.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    signOutDialog = false
                    scope.launch { backup.signOut() }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { signOutDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusRow(status: BackupStatus, now: Long, signIn: GoogleSignIn, onResolve: () -> Unit, onBackUpNow: () -> Unit) {
    when (val problem = status.problem) {
        is BackupProblem.Conflict -> ListRow(
            "Choose which data to keep",
            subtitle = "Your Drive has a backup from ${describe(problem.cloud)}. Backups are paused until you choose.",
            icon = Icons.Rounded.SyncProblem,
            iconTint = MaterialTheme.colorScheme.error,
            onClick = onResolve,
        )
        BackupProblem.Reconnect -> ListRow(
            if (signIn.busy) "Signing in…" else "Sign in again",
            subtitle = "Google needs you to sign in again before Platebit can keep backing up.",
            icon = Icons.Rounded.SyncProblem,
            iconTint = MaterialTheme.colorScheme.error,
            onClick = signIn.start,
        )
        else -> {
            val last = status.lastBackupAt?.let { "Last backup ${ago(it, now)}" }
            ListRow(
                when {
                    status.running -> "Backing up…"
                    else -> "Back up now"
                },
                subtitle = when {
                    problem is BackupProblem.Failed -> "Couldn't back up: ${problem.message}. Retrying by itself" + (last?.let { ". $it" } ?: "")
                    status.pending && !status.running -> "Changes waiting to back up" + (last?.let { " · $it" } ?: "")
                    last != null -> "$last. Backs up by itself after every change"
                    else -> "Not backed up yet"
                },
                icon = if (status.running || status.pending) Icons.Rounded.CloudSync else Icons.Rounded.CloudDone,
                onClick = if (status.running) null else onBackUpNow,
            )
        }
    }
}

/**
 * Picks a backup to restore. For a conflict, the other choice is keeping this phone's data,
 * which replaces the newest backup and so takes a second tap.
 */
@Composable
internal fun ChooseBackupDialog(
    points: List<RestorePoint>,
    conflict: Boolean,
    onRestore: (RestorePoint) -> Unit,
    onKeepPhone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(points.first()) }
    var armed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(if (conflict) "Backup found in Google Drive" else "Restore a backup") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (conflict) {
                        "Restore it to this phone, or keep what's on this phone and make that the backup instead."
                    } else {
                        "Replaces the meals, water, weights, profile and settings on this phone. API keys stay as they are."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                points.forEach { point ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = point == selected, role = Role.RadioButton) { selected = point }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = point == selected, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                if (point.latest) "Latest backup" else "Copy from ${point.info.savedAt?.let { day(it) } ?: "earlier"}",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                describe(point.info),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRestore(selected) }) { Text("Restore") } },
        dismissButton = {
            if (conflict) {
                TextButton(onClick = { if (armed) onKeepPhone() else armed = true }) {
                    Text(if (armed) "Tap again to replace backup" else "Keep this phone's data", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** "Restore from Google Drive" on the welcome screen, for setting up a new phone. */
@Composable
fun RestoreFromDriveButton(backup: CloudBackup, onRestored: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var points by remember { mutableStateOf<List<RestorePoint>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    val noBackup = "There's no backup in this Google account yet. Set up your profile and Platebit will back it up from now on."

    val signIn = rememberGoogleSignIn(
        backup,
        onConnected = { result ->
            when (result) {
                CloudBackup.Connected.Fresh -> message = noBackup
                is CloudBackup.Connected.Found -> points = result.points
                CloudBackup.Connected.Linked -> scope.launch {
                    runCatching { backup.restorePoints() }
                        .onSuccess { if (it.isEmpty()) message = noBackup else points = it }
                        .onFailure { message = describeSignInError(it) ?: "Couldn't reach Google Drive." }
                }
            }
        },
        onError = { message = it },
    )

    TextButton(onClick = signIn.start, enabled = !signIn.busy && !restoring, modifier = modifier) {
        Text(
            when {
                restoring -> "Restoring…"
                signIn.busy -> "Signing in…"
                else -> "Restore from Google Drive"
            },
        )
    }

    points?.let {
        ChooseBackupDialog(
            points = it,
            conflict = false,
            onRestore = { point ->
                points = null
                scope.launch {
                    restoring = true
                    runCatching { backup.restore(point) }
                        .onSuccess { restored ->
                            if (restored.profile != null) onRestored() else message = "Restored. Now finish setting up your profile."
                        }
                        .onFailure { e -> message = "Restore failed: ${e.message}" }
                    restoring = false
                }
            },
            onKeepPhone = {},
            onDismiss = { points = null },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

/** "Galaxy S25 · Oct 19, 14:02 · 230 meals" */
private fun describe(info: CloudInfo): String = listOfNotNull(
    info.deviceName,
    info.savedAt?.let { stamp(it) },
    info.meals?.let { if (it == 1) "1 meal" else "$it meals" },
).joinToString(" · ").ifEmpty { "an earlier install" }

private fun stamp(millis: Long): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun day(millis: Long): String =
    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun ago(millis: Long, now: Long): String {
    val minutes = (now - millis) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> stamp(millis)
    }
}
