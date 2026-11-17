# GKeep

An Android app that shows the last 24 hours of your Gmail inbox. You tap **Keep** on the emails you want, and everything else is cleared out.

## What it does

| | |
|---|---|
| **Inbox** | Lists every email that reached your Gmail inbox in the last 24 hours, newest first. |
| **Keep** | Tap an email (or its Keep chip) to keep it. Kept emails are left untouched in Gmail and stay unread. |
| **Delete** | The **Delete N** button moves every un-kept email to Gmail's Trash, after one confirmation. Gmail empties Trash after 30 days. |
| **Remembered senders** | When you keep an email, that sender is remembered. Their future emails start out marked Keep when you open the app, and you can untick any of them. Manage the list under **Senders** (the person icon). |
| **Auto-delete** | Once you've deleted more than 10 emails from a sender, GKeep creates a Gmail filter that sends all their future mail to Trash. GKeep also trashes any of their mail it finds during a sync. You can unblock a sender under **Senders**. Senders you keep are never auto-deleted. |
| **Notifications** | Optional. GKeep checks about every 15 minutes, which is the minimum Android allows, and can be limited to kept senders only. |

To change the limit of 10, edit `AUTO_DELETE_AFTER` in `app/src/main/java/com/gkeep/app/logic/InboxManager.kt`.

## One-time Google setup (about 10 minutes)

Gmail only lets apps in that you've registered with Google. You do this once.

1. Go to <https://console.cloud.google.com/>, create a project (e.g. "GKeep"), and select it.
2. **APIs & Services → Library**: search for **Gmail API** and click **Enable**.
3. **Google Auth Platform → Branding / Audience** (called "OAuth consent screen" in older layouts):
   - User type: **External**. App name: **GKeep**. Add your email as the support and developer contact.
   - Under **Audience → Test users**, add your own Gmail address.
4. **Data Access → Add scopes**: add these two:
   - `https://www.googleapis.com/auth/gmail.modify`
   - `https://www.googleapis.com/auth/gmail.settings.basic`
5. **Clients → Create client → Android**:
   - Package name: `com.gkeep.app`
   - SHA-1: `5B:42:39:29:2B:03:C0:03:1A:30:C1:EC:06:58:2D:63:A3:0B:A9:75`

   This fingerprint belongs to the signing key included in the project (`app/gkeep-debug.keystore`), so it works on any computer you build on.

You don't need to copy any client ID into the code. Google Play services matches the app by its package name and SHA-1.

## Build and run

1. Open the `GKeep` folder in Android Studio (Ladybug or newer). Let Gradle sync; it downloads everything it needs. If it offers to upgrade the Android Gradle Plugin or libraries, accepting is fine.
2. Plug in your phone with USB debugging on, or use an emulator that has Google Play. Press **Run**.
3. Tap **Connect Gmail**, choose your account, and approve access. You'll see a "Google hasn't verified this app" screen; that's expected for your own app. Tap **Continue**.

## Good to know

- **Weekly re-sign-in in testing mode.** While your Google project is in "Testing", Google expires Gmail access after about 7 days. GKeep then shows a "sign in again" notification, and one tap fixes it. To avoid this, set the project's **Audience → Publishing status** to **In production** without submitting for verification. It stays limited to 100 users, which is fine for personal use, and keeps showing the unverified warning at sign-in.
- **Trash, not permanent delete.** Permanent deletion would need Google's full-access Gmail scope. Trash is safer and Gmail empties it automatically after 30 days.
- **Kept emails stay unread.** GKeep only reads headers and snippets (sender, subject, preview), and that never marks a message as read.
- **"Last 24 hours" means currently in your inbox.** Emails you've already archived or deleted in Gmail won't appear.
- **Threads.** Gmail groups emails into conversations. GKeep acts on individual emails, so deleting the newest message of a thread leaves any older messages of that thread wherever they were.
- **Your data stays on your phone.** The app talks only to Google's Gmail API, and the sender list is stored locally on your phone.

## Project layout

```
app/src/main/java/com/gkeep/app/
  MainActivity.kt          screen switching + Google consent handling
  GKeepApp.kt              app startup, dependency wiring
  auth/GmailAuth.kt        OAuth tokens via Google Play services
  gmail/GmailClient.kt     Gmail REST API: list, read headers, trash, filters
  gmail/GmailModels.kt     API data classes
  data/Data.kt             Room database (senders) + settings
  logic/InboxManager.kt    the keep / delete / auto-delete rules
  work/Background.kt       15-minute background sync + notifications
  ui/InboxViewModel.kt     screen state
  ui/Screens.kt            Inbox, Senders, Settings, Sign-in (Jetpack Compose)
```
