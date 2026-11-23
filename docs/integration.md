# TTMailer Android SDK – integration guide

This guide walks through adding the TTMailer SDK to an Android app, from the setup in TTMailer to release. For a quick
overview see the [README](../README.md).

- [1. What the SDK does](#1-what-the-sdk-does)
- [2. Set up the app in TTMailer](#2-set-up-the-app-in-ttmailer)
- [3. Add the SDK to the project](#3-add-the-sdk-to-the-project)
- [4. Start the SDK](#4-start-the-sdk)
- [5. Push notifications](#5-push-notifications)
- [6. User accounts](#6-user-accounts)
- [7. Newsletter sign-up](#7-newsletter-sign-up)
- [8. Reset and account deletion](#8-reset-and-account-deletion)
- [9. How the SDK behaves](#9-how-the-sdk-behaves)
- [10. Privacy](#10-privacy)
- [11. Testing and troubleshooting](#11-testing-and-troubleshooting)
- [12. API reference](#12-api-reference)
- [13. The server API](#13-the-server-api)

## 1. What the SDK does

TTMailer sends e-mails and push notifications to a project's contacts. Apps that have their own backend sync their users
to TTMailer from that backend. Apps without a backend use this SDK instead:

- **The installation.** Each installation of the app is a contact in TTMailer, with a device that holds the FCM token.
  The contact needs no e-mail address, so pushes work right after the first start.
- **The push switch.** The app's notification setting decides whether the contact gets pushes of the app's push topic.
- **The account.** Apps with sign-in pass their account id, so all devices of one user become one contact.
- **The newsletter.** The user enters an e-mail address in the app and TTMailer sends a confirmation e-mail. Only after
  the confirmation does the address join the installation's contact. Without a confirmation nothing is joined, so
  nobody can attach someone else's address.
- **Push opens.** A tap on a TTMailer push is reported back, so open rates and automations work.

The SDK is written in Kotlin and has no dependencies; it does not even depend on Firebase, the app passes the token.
It supports Android 7+ (minSdk 24) and can be called from Kotlin (callbacks or coroutines) and Java.

## 2. Set up the app in TTMailer

Someone with access to the TTMailer project does this once per app (iOS and Android are two separate apps):

1. **Push notifications** (Nastavení → Push notifikace): the project needs FCM credentials (the service account of the
   app's Firebase project) for the app's package.
2. **Push topic** (Nastavení → Témata odběru): a topic with the channel *push*, e.g. "App notifications". The app's
   notification switch writes this topic.
3. **Sign-up form** (Nastavení → Formuláře), only for the in-app newsletter: a form with **confirmation turned on**. It
   sets the newsletter topics, the confirmation e-mail and its texts. A form without confirmation cannot be used.
4. **The app** (Nastavení → Aplikace (SDK)) → Add:
   - name: e.g. "Storky Android";
   - platform: Android;
   - bundle id: the `applicationId`, e.g. `com.tappytaps.storky`;
   - the form and the push topic from steps 2 and 3.

   TTMailer shows the app key `tta_…`.

The app key is **not a secret**. It ships inside the app and anyone can extract it, so it is fine in source code and
in public repositories. It only lets the app write its own installation, and the address always needs the
confirmation. If a key is abused, use *Rotate key* in TTMailer and release an app update with the new key; the old key
stops working at once. *Disable* stops an app without deleting it.

## 3. Add the SDK to the project

The library is built by [JitPack](https://jitpack.io/#tappytaps/ttmailer-sdk-android) from the tags of this repository.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.tappytaps:ttmailer-sdk-android:0.1.0")
}
```

With a version catalog:

```toml
[libraries]
ttmailer = { module = "com.github.tappytaps:ttmailer-sdk-android", version = "0.1.0" }
```

The library declares the `INTERNET` permission and ships its ProGuard / R8 rules; nothing else is needed. Firebase
Messaging is added by the app as usual (`com.google.firebase:firebase-messaging`).

## 4. Start the SDK

Call `configure` once, in `Application.onCreate`:

```kotlin
class StorkyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TTMailer.configure(this, appKey = "tta_XXXXXXXXXXXXXXXXXXXXXXXX")
    }
}
```

`configure` parameters:

| Parameter | Default | Meaning |
|---|---|---|
| `context` | — | Any context; the SDK keeps the application context. |
| `appKey` | — | The app key from TTMailer. |
| `baseUrl` | `https://mailer.tappytaps.com` | The TTMailer server; change it only for a test server. |
| `linkKey` | `redirectUrl` | The push data key that holds the push's link. It must match the project's push format in TTMailer. |

From then on the SDK keeps itself up to date: it sends the installation at start and whenever an activity resumes.
Every other method may be called from any thread. The work runs on the SDK's own background thread, and callbacks
come on the main thread.

From Java: `TTMailer.configure(this, "tta_…");`, and the other methods are static as well.

## 5. Push notifications

### The token

```kotlin
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = TTMailer.setPushToken(token)
}

// Once at start (e.g. in Application.onCreate after configure), since onNewToken does not run on every start:
FirebaseMessaging.getInstance().token.addOnSuccessListener { TTMailer.setPushToken(it) }
```

The SDK sends the token only when it changed.

### Permission and the switch

On Android 13+ the app must ask for `POST_NOTIFICATIONS`. `setPushEnabled` tells TTMailer whether the user wants
notifications. It subscribes or unsubscribes the contact from the app's push topic. This is the user's own choice, so
it also overrides an earlier unsubscribe.

```kotlin
val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    TTMailer.setPushEnabled(granted)
}

// At start and in onResume: the user may have turned notifications off in the system settings.
TTMailer.setPushEnabled(NotificationManagerCompat.from(this).areNotificationsEnabled())
```

If the app has its own in-app switch, send `appSwitch && systemAllowed`.

### Taps

TTMailer pushes carry `ttmToken` (for the open report) and usually a link under `redirectUrl` in their data.

**The app is in the background or not running.** FCM shows the notification itself. A tap opens the launcher
activity, and the push's data is in the intent extras:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handlePush(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePush(intent)
    }
    private fun handlePush(intent: Intent) {
        TTMailer.opened(intent.extras)?.let { link -> Router.open(this, Uri.parse(link)) }
    }
}
```

**The app is in the foreground.** `onMessageReceived` gets the push, and the app shows its own notification. Put the
data into the notification's intent, then handle it the same way:

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    val intent = Intent(this, MainActivity::class.java).apply {
        message.data.forEach { (k, v) -> putExtra(k, v) }
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val pending = PendingIntent.getActivity(this, message.messageId.hashCode(), intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    // … build and show the notification with setContentIntent(pending)
}
```

`TTMailer.opened(Map<String, String?>)` takes `RemoteMessage.data` directly, if the app handles the data some other way.

`opened` returns the link at once and reports the open in the background. A launch without a push returns `null` and
sends nothing. Pushes from other sources (no `ttmToken`) are not reported. An open made offline is kept and sent later
(the last 100 at most).

## 6. User accounts

Skip this section if the app has no sign-in.

```kotlin
TTMailer.setUserId(account.id)   // after sign-in, and at start while signed in
TTMailer.setUserId(null)         // after sign-out
```

- **The first sign-in.** The installation's anonymous contact becomes the user's contact, and it keeps a confirmed
  address and its consents. If the user already has a contact (another phone), this device moves to it, so pushes go
  to all of the user's devices.
- **Sign-out.** The installation starts a new anonymous contact. The user's contact keeps nothing of this phone.
- **The id must be unguessable**, e.g. an account UUID, never an e-mail address or a sequential number. Anyone with the
  app key and a user's id could move a device to that user.
- Calling it repeatedly with the same id is free: nothing is sent when nothing changed.

## 7. Newsletter sign-up

With coroutines:

```kotlin
lifecycleScope.launch {
    try {
        TTMailer.subscribe(email)
        showMessage("Check your inbox and confirm the subscription.")
    } catch (e: TTMailerException) {
        showMessage(when (e.reason) {
            TTMailerException.Reason.INVALID_EMAIL -> "This doesn't look like an e-mail address."
            TTMailerException.Reason.RATE_LIMITED -> "Too many attempts. Try again in a while."
            else -> "Something went wrong. Try again later."
        })
    }
}
```

With a callback (also from Java), which runs on the main thread:

```kotlin
TTMailer.subscribe(email) { result ->
    result.onSuccess { showCheckInbox() }.onFailure { showError(it as TTMailerException) }
}
```

- TTMailer always answers the same way, whatever the address (already subscribed, unsubscribed, new), so the app
  cannot learn who is subscribed. Show "check your inbox" every time.
- The confirmation e-mail uses the device language. Pass `language` (e.g. `"cs"`) to choose another one.
- **After the confirmation** the address joins the contact this installation belongs to at that moment, also after a
  sign-in in the meantime. From then on e-mails and pushes go to one contact.
- If the address already belonged to someone in TTMailer before this sign-up (web form, import, another app user), it
  is only subscribed and never merged into the installation. So the app key can never take over a known person.
- `NO_FORM` and `FORM_WITHOUT_CONFIRMATION` are setup errors (section 2): the app has no form, or the form does not
  confirm.

## 8. Reset and account deletion

```kotlin
TTMailer.deleteContact()   // suspend; the user deleted their account in the app (GDPR erasure)
TTMailer.reset()           // suspend; a "reset app" action, or opting out of TTMailer
// or with callbacks: TTMailer.deleteContact { result -> … }
```

- `deleteContact()` erases the installation's contact in TTMailer: its address, devices, consents and history. It only
  erases contacts the SDK writes, never one that a backend syncs.
- `reset()` removes this device. It also removes the anonymous contact when that has no address. A signed-in user's
  contact stays.
- Both start a new installation (a new id) and forget the token, the switch and the user id. Call `setPushToken` /
  `setPushEnabled` again if the app keeps running with pushes.
- Both fail on network errors (`Reason.NETWORK`), so the app can retry. Nothing changes locally until TTMailer
  confirms.

## 9. How the SDK behaves

- **When it sends.**
  - The installation is sent at start, on every change and when an activity resumes.
  - It holds the installation id (a random UUID), the token, the switch, the user id, the device language, time zone,
    app version (`versionName`) and Android version.
  - It is not sent when nothing changed and the last send is less than a day old. The daily send keeps "last active in
    the app" on the contact.
- **Offline.**
  - Updates never fail from the app's point of view; the SDK keeps the state and sends it later.
  - A failed send is retried with a growing pause (30 s, doubling up to an hour) while the app runs, and again on the
    next start.
  - Opens are queued.
- **An unknown or disabled key** (404) is retried after a day, not on every start.
- **Storage.** `SharedPreferences` named `com.tappytaps.ttmailer.sdk`.
  - Uninstalling the app clears it; a reinstall is a new installation.
  - TTMailer removes the old device when the new installation sends the same token, so a push never arrives twice.
  - If the app uses Auto Backup, exclude this file so a restored backup does not reuse another device's installation:

  ```xml
  <!-- res/xml/backup_rules.xml (Android 11 and lower: full-backup-content) -->
  <data-extraction-rules>
      <cloud-backup><exclude domain="sharedpref" path="com.tappytaps.ttmailer.sdk.xml"/></cloud-backup>
      <device-transfer><exclude domain="sharedpref" path="com.tappytaps.ttmailer.sdk.xml"/></device-transfer>
  </data-extraction-rules>
  ```

- **Threads.** One background thread for the SDK's work; callbacks run on the main thread. Nothing blocks the caller.
- **Size.** No dependencies, no WorkManager, no services: the app calls the SDK where it wants to.

## 10. Privacy

The SDK sends only what is listed in section 9, plus an e-mail address the user types for the newsletter. It does not
use the advertising id.

For the Google Play *Data safety* form:

| Data type | Collected | Shared | Purpose |
|---|---|---|---|
| Device or other IDs (the installation id and FCM token) | yes | no | App functionality |
| Email address (only with the newsletter) | yes | no | App functionality, marketing |
| User IDs (only with `setUserId`) | yes | no | App functionality |
| App interactions (push opens) | yes | no | Analytics |

Data is encrypted in transit (HTTPS). Users can ask for deletion; the app does that with `deleteContact()`.

## 11. Testing and troubleshooting

- **Does TTMailer see the installation?** In TTMailer → Kontakty, find the contact with external id
  `sdk:i:<installation id>`, or `sdk:u:<user id>` after a sign-in. It should have a device `sdk:<installation id>`.
  `TTMailer.installationId` holds the id; log it in debug builds.
- **No device on the contact.** The app did not pass a token. Check that `setPushToken` is called at start, not only in
  `onNewToken`.
- **The device is there, but pushes do not arrive.** Check the project's FCM credentials and the package of the app in
  TTMailer, and that notifications are allowed for the app (Android 13+).
- **The push arrives, but the open is not counted.** The launcher activity must call `TTMailer.opened(intent.extras)`
  in `onCreate` and `onNewIntent`. A notification the app builds itself must carry the push's data in its intent.
- **`subscribe` fails with `NO_FORM` / `FORM_WITHOUT_CONFIRMATION`.** Fix the app's form in TTMailer (section 2).
- **Everything fails with a 404.** The key is wrong, rotated or disabled, or the project is archived.
- **A test server.** `TTMailer.configure(this, appKey = "…", baseUrl = "https://…")` with a key from that server.

## 12. API reference

All on the `TTMailer` object (`@JvmStatic`).

| Method | Description |
|---|---|
| `configure(context, appKey, baseUrl, linkKey)` | Starts the SDK. Call once in `Application.onCreate`. |
| `setPushToken(token: String)` | The FCM registration token. |
| `setPushEnabled(enabled: Boolean)` | The user's notification choice; writes the app's push topic. |
| `setUserId(userId: String?)` | The account id after sign-in; `null` after sign-out. |
| `subscribe(email, language?, callback)` / `suspend subscribe(email, language?)` | The newsletter sign-up with a confirmation e-mail. |
| `opened(extras: Bundle?): String?` / `opened(data: Map<String, String?>): String?` | Reports a tap on a push; returns its link. |
| `reset(callback)` / `suspend reset()` | Removes this installation and starts a new one. |
| `deleteContact(callback)` / `suspend deleteContact()` | Erases the user's contact (account deletion). |
| `installationId: String?` | The current installation id (`null` before `configure`). |

`TTMailerException.reason`:

| Reason | When |
|---|---|
| `NOT_CONFIGURED` | `configure` was not called. |
| `INVALID_EMAIL` | Not a valid address. |
| `NO_FORM` | The app has no sign-up form in TTMailer, or it is disabled. |
| `FORM_WITHOUT_CONFIRMATION` | The app's form does not confirm. |
| `RATE_LIMITED` | Too many requests from this network. |
| `NETWORK` | No connection or a timeout. |
| `SERVER` | Any other answer (`status`, `code`). |

## 13. The server API

The SDK is a thin client of TTMailer's public SDK API. Every call carries the header `X-TTMailer-App-Key`.

| Call | Used by |
|---|---|
| `PUT /sdk/v1/installations/{installationId}` with `{pushToken, pushEnabled, language, timeZone, appVersion, osVersion, userId}` | sync |
| `POST /sdk/v1/installations/{installationId}/subscribe` with `{email, language}` | `subscribe` |
| `DELETE /sdk/v1/installations/{installationId}` | `reset` |
| `DELETE /sdk/v1/installations/{installationId}/contact` | `deleteContact` |
| `POST /p/open` with `{t: ttmToken, at}` (no key) | `opened` |

Limits:
- 300 calls per IP address and minute;
- 20,000 new installations per app and day (`429`).

An unknown or disabled key answers `404`.
