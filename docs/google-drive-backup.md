# Google Drive backup (removed for now)

Platebit briefly had an automatic backup to the user's own Google Drive. It was removed from the
app because it can't work until the app is registered with Google. This page explains how it
worked, why it was blocked, and how to bring it back.

## How it worked

- **Access:** signed in through Google Play services' `AuthorizationClient` and asked only for
  the **`drive.appdata`** scope, plus email and profile. That's a hidden app folder in the user's
  Drive; the app can't see any other file.
- **Content:** the same JSON as *Settings → Export backup*, gzipped, holding profile, meals,
  water, weights and non-secret settings. **API keys and photos were never included.**
- **When:** any data change queued an upload (WorkManager), and restore was offered on a fresh install.
- **Erasing:** *Erase everything* also deleted the Drive copy.
- **Files:**
  - `backup/CloudBackup.kt`, `DriveClient.kt`, `GoogleAuth.kt`, `BackupFormat.kt`
  - `ui/settings/BackupSection.kt`
  - dependencies `play-services-auth`, `kotlinx-coroutines-play-services`, `work-runtime-ktx`

## Why it was blocked

Signing in failed right after the account picker. Google returned **`UNREGISTERED_ON_API_CONSOLE`**
(seen as error code 8 / DEVELOPER_ERROR 10): *"This android application is not registered to use
OAuth2.0"*. There's no client secret to embed. Google recognizes an Android app only by its
**package name + signing certificate**, registered in a Google Cloud project.

## Registering the app (one time, with your Google account)

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and create a project (e.g. "Platebit").
2. Under **APIs & Services → Library**, enable the **Google Drive API**.
3. Under **OAuth consent screen**, choose **External**, fill in the app name and your email, and add yourself under **Test users**.
4. Under **Credentials → Create credentials → OAuth client ID**, choose type **Android**:
   - Package name: `app.morsel`
   - SHA-1: `E4:C0:55:A5:CB:8C:F1:E5:76:C9:3F:C0:DA:80:0A:AB:7F:09:6D:41` (the current signing key)
   - If the app ever gets a new release key, add that key's SHA-1 too.
5. Wait a few minutes. No app change is needed for the registration itself.

## Bringing it back

The code is intact in git history. Restore it from these commits (oldest first):

| Commit | What |
|---|---|
| `57edf64` | Automatic backup to the user's own Google Drive |
| `4305b4d` | Erase everything also deletes the Google Drive backup |
| `aa18a62` | Clear message for an unregistered sign-in |
| `cf279bc` | Greyed out "Coming in a later version" (drop this gate) |

The removal commit (search the log for "Remove Google Drive backup") lists exactly what was taken
out, so reverting it brings everything back. Then re-check that the backup still excludes API keys.
