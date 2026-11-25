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
- [8. Language, tags and premium](#8-language-tags-and-premium)
- [9. Reset and account deletion](#9-reset-and-account-deletion)
- [10. How the SDK behaves](#10-how-the-sdk-behaves)
- [11. Privacy](#11-privacy)
- [12. Testing and troubleshooting](#12-testing-and-troubleshooting)
- [13. API reference](#13-api-reference)
- [14. The server API](#14-the-server-api)

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
- **Rich push (0.4.0).** A push can open a web page in the browser or in Custom Tabs, or show an in-app message
  written in TTMailer, without any code in the app beyond `TTMailer.handle`.

The SDK is written in Kotlin. Its only dependency is `androidx.browser` (Custom Tabs, for the rich push); it does not
even depend on Firebase, the app passes the token. It supports Android 7+ (minSdk 24) and can be called from Kotlin (callbacks or coroutines) and Java.

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
    implementation("com.github.tappytaps:ttmailer-sdk-android:0.4.0")
}
```

With a version catalog:

```toml
[libraries]
ttmailer = { module = "com.github.tappytaps:ttmailer-sdk-android", version = "0.4.0" }
```

The library declares the `INTERNET` permission, the in-app message activity and a `<queries>` entry for Custom Tabs,
and ships its ProGuard / R8 rules; nothing else is needed. It brings `androidx.browser:browser:1.8.0` (Gradle
resolves a newer one if the app has it). Firebase
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

TTMailer pushes carry `ttmToken` (for the open report) and usually a link under `redirectUrl` in their data. Pushes
sent with an SDK action (TTMailer: *Po klepnutí*) also carry `ttmAction` and `ttmUrl`.

Call `TTMailer.handle(activity, …)` when the user taps a notification. It reports the open and does what the push
says:

| Po klepnutí (`ttmAction`) | What `handle` does | Returns |
|---|---|---|
| Otevřít aplikaci (absent, `app`) | Nothing more: the app opens the link itself, as before. | the push's link (or `null`) |
| Prohlížeč (`browser`) | Opens `ttmUrl` in the system browser. | `null` |
| V aplikaci (`inapp`) | Opens `ttmUrl` in Custom Tabs (the system browser when no installed browser has Custom Tabs). | `null` |
| Zpráva v aplikaci (`message`) | Shows the message full screen over the activity (below). | `null` |

Links to App Store or Google Play (`play.google.com/store…`, `market://`, `apps.apple.com`, `itunes.apple.com`,
`itms-apps://`) always open the store: the Play Store app, or the browser.

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
        TTMailer.handle(this, intent.extras)?.let { link -> Router.open(this, Uri.parse(link)) }
    }
}
```

Call `handle` once per tap, on the main thread: with `savedInstanceState == null` in `onCreate`, so a rotation does not
open the message again.

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

`TTMailer.handle(activity, Map<String, String?>)` takes `RemoteMessage.data` directly, if the app handles the data some
other way.

`handle` returns at once and reports the open in the background. A launch without a push returns `null` and does
nothing. Pushes from other sources (no `ttmToken`) are not reported, but their link is returned. An open made offline
is kept and sent later (the last 100 at most). With an SDK action the open also counts as the tap.

`TTMailer.opened(…)` still works as before (it reports the open and returns the link, but ignores `ttmAction`). It is
deprecated in these docs in favour of `handle`: an app that keeps `opened` opens every push's link itself, so the
browser and in-app browser pushes still work (the link), but a message opens as a web page.

### The in-app message

A push with *Zpráva v aplikaci* shows a page written in TTMailer (`ttmUrl`, `…/p/m/<token>`) in the SDK's own
full-screen activity, `TTMailerMessageActivity`, over the activity passed to `handle`:

- A close button at the top, Back closes it too. The colours follow the system's light or dark mode.
- JavaScript is off. The first load carries the header `X-TTMailer-SDK: <version>` (TTMailer then shows the message's
  close links, `ttmailer://close`).
- No network, or an error answer (HTTP 400 and above): an error with *Zkusit znovu* / *Try again*.
- Links:
  - pages on the TTMailer server (the click redirects, so the click is counted) stay in the message;
  - `ttmailer://close` closes the message;
  - a store link opens the store, another web page opens in Custom Tabs; the message stays open below;
  - a deep link (any other scheme, e.g. `myapp://premium`) closes the message, then goes to `TTMailer.onDeepLink`:

```kotlin
// Application.onCreate, after configure:
TTMailer.onDeepLink = { link -> Router.open(currentActivity, Uri.parse(link)) }
```

  Without `onDeepLink` the system opens the deep link (the app's own intent filters, like a link from another app).

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

## 8. Language, tags and premium

### The app's own language

```kotlin
TTMailer.setLanguage("de")   // the user picked German in the app
TTMailer.setLanguage(null)   // back to the system language
```

TTMailer writes to the user in this language (e-mails and pushes with language variants). Kept between launches.

### Tags

```kotlin
TTMailer.addTags(listOf("sportovec"))
TTMailer.removeTags(listOf("beta"))
```

Tags of the user's contact, for audiences in TTMailer (e.g. people who use one feature). Keys: a-z, 0-9, `_` and `-`,
40 at most; uppercase is lowered. They wait offline and are sent with the installation. A tag the SDK adds can be
removed by the team in TTMailer and vice versa.

### App activity

TTMailer learns when the user last used the app: the SDK reports it at launch and when the app becomes active, at most
once a day. On by default; audiences in TTMailer can then pick e.g. "used the app in the last 30 days". To turn it off
(e.g. when the user opts out of analytics):

```kotlin
TTMailer.setActivityTracking(false)
```

Kept between launches. Off, the installation is still sent (when it changes, and once a day so TTMailer keeps it), but
never counts as activity. To make sure the very first launch reports nothing, call it right after `configure`.

### Premium (purchases)

TTMailer keeps the user's premium (until when, its kind, since when) for audiences like "trial ended, did not buy".
Two ways, pick one:

**Automatic (off by default).** The SDK reads the user's purchases from Play Billing at launch and whenever the app
comes to the foreground. The app must use Play Billing 7 or newer (the SDK uses the app's own library):

```kotlin
TTMailer.configure(this, "tta_…")
TTMailer.enableAutomaticPurchases()
```

An app that also sells add-ons (e.g. a theme pack) names the products that unlock the app:
`enableAutomaticPurchases(setOf("pro_lifetime"))`.

- An active subscription: premium until its next renewal, kind `paid`. Play does not tell the app the end or a free
  trial: the SDK counts the renewal from the purchase by the product's billing period (a month if Play does not say).
  A cancelled subscription is premium until the user opens the app after it lapsed.
- A one-time purchase that unlocks the app: a lifetime premium. Without `lifetimeProductIds` that is every
  non-consumable; with it only the products listed.
- Every one-time product owned (acknowledged; consumables are not reported): a tag `koupeno_<product>` (lowercase, other characters
  `_`), e.g. `koupeno_themes_pack`. A refunded product loses its tag.
- A refunded product or subscription ends the premium the next time the app is opened.
- Consumables (coins, credits) are not reported.
- The SDK only reads: it never acknowledges or consumes purchases; the app keeps doing that.

**Manual**, e.g. with RevenueCat:

```kotlin
TTMailer.setPremium(untilMillis = ent.expirationDate!!.time, kind = if (ent.periodType == PeriodType.TRIAL) "trial" else "paid")
TTMailer.setLifetimePremium()
```

A date in the past says the premium ended. Each is sent once, again only when it changes.

The purchases are not verified with Google: anyone could send any premium for their own installation (never for another
user). That is fine for marketing audiences; never grant anything because of them.

## 9. Reset and account deletion

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

## 10. How the SDK behaves

- **When it sends.**
  - The installation is sent at start, on every change and when an activity resumes.
  - It holds the installation id (a random UUID), the token, the switch, the user id, the device language, time zone,
    app version (`versionName`), Android version and the SDK version (`sdkVersion`, so TTMailer knows which devices
    can show rich pushes).
  - It is not sent when nothing changed and the last send is less than a day old. The daily send keeps "last active in
    the app" on the contact.
- **Offline.**
  - Updates never fail from the app's point of view; the SDK keeps the state and sends it later.
  - A failed send is retried with a growing pause (30 s, doubling up to an hour) while the app runs, and again on the
    next start.
  - Opens, tags and the premium are queued and sent after the installation.
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
- **Size.** One small dependency (`androidx.browser`), no WorkManager, no services: the app calls the SDK where it
  wants to. The in-app message is a plain `Activity` with a system `WebView`; no AppCompat.

## 11. Privacy

The SDK sends only what is listed in section 10, plus an e-mail address the user types for the newsletter. It does not
use the advertising id.

For the Google Play *Data safety* form:

| Data type | Collected | Shared | Purpose |
|---|---|---|---|
| Device or other IDs (the installation id and FCM token) | yes | no | App functionality |
| Email address (only with the newsletter) | yes | no | App functionality, marketing |
| User IDs (only with `setUserId`) | yes | no | App functionality |
| App interactions (push opens) | yes | no | Analytics |
| Purchase history (only with the premium, automatic or `setPremium`) | yes | no | Marketing |

Data is encrypted in transit (HTTPS). Users can ask for deletion; the app does that with `deleteContact()`.

## 12. Testing and troubleshooting

- **Does TTMailer see the installation?** In TTMailer → Kontakty, find the contact with external id
  `sdk:i:<installation id>`, or `sdk:u:<user id>` after a sign-in. It should have a device `sdk:<installation id>`.
  `TTMailer.installationId` holds the id; log it in debug builds.
- **No device on the contact.** The app did not pass a token. Check that `setPushToken` is called at start, not only in
  `onNewToken`.
- **The device is there, but pushes do not arrive.** Check the project's FCM credentials and the package of the app in
  TTMailer, and that notifications are allowed for the app (Android 13+).
- **The push arrives, but the open is not counted.** The launcher activity must call `TTMailer.handle(this, intent.extras)`
  in `onCreate` and `onNewIntent`. A notification the app builds itself must carry the push's data in its intent.
- **`subscribe` fails with `NO_FORM` / `FORM_WITHOUT_CONFIRMATION`.** Fix the app's form in TTMailer (section 2).
- **Everything fails with a 404.** The key is wrong, rotated or disabled, or the project is archived.
- **A browser / in-app / message push opens the link like a normal push.** The app still calls `opened` instead of
  `handle`, or runs SDK 0.3.x (TTMailer counts the devices on 0.4.0+ in the push editor).
- **A message shows the error.** The device is offline, or TTMailer answered with an error. *Try again* loads it
  again. A message older than 30 days is not an error: TTMailer's page says it is no longer available.
- **A test server.** `TTMailer.configure(this, appKey = "…", baseUrl = "https://…")` with a key from that server.

## 13. API reference

All on the `TTMailer` object (`@JvmStatic`).

| Method | Description |
|---|---|
| `configure(context, appKey, baseUrl, linkKey)` | Starts the SDK. Call once in `Application.onCreate`. |
| `setPushToken(token: String)` | The FCM registration token. |
| `setPushEnabled(enabled: Boolean)` | The user's notification choice; writes the app's push topic. |
| `setUserId(userId: String?)` | The account id after sign-in; `null` after sign-out. |
| `subscribe(email, language?, callback)` / `suspend subscribe(email, language?)` | The newsletter sign-up with a confirmation e-mail. |
| `handle(activity, extras: Bundle?): String?` / `handle(activity, data: Map<String, String?>): String?` | Reports a tap on a push and does its action (browser, Custom Tabs, message); returns the link for the app, or `null` when the SDK handled it. Main thread. |
| `onDeepLink: ((String) -> Unit)?` | Deep links from an in-app message (main thread); `null` = the system opens them. |
| `opened(extras: Bundle?): String?` / `opened(data: Map<String, String?>): String?` | Reports a tap on a push; returns its link. Ignores the action: use `handle`. |
| `SDK_VERSION` | This SDK's version (`"0.4.0"`). |
| `reset(callback)` / `suspend reset()` | Removes this installation and starts a new one. |
| `deleteContact(callback)` / `suspend deleteContact()` | Erases the user's contact (account deletion). |
| `installationId: String?` | The current installation id (`null` before `configure`). |
| `setActivityTracking(enabled: Boolean)` | App activity reporting (on by default). |
| `setLanguage(language: String?)` | The app's own language; `null` = the system's. |
| `addTags(tags: List<String>)` / `removeTags(tags: List<String>)` | Tags of the user's contact. |
| `setPremium(untilMillis, kind?, startedAtMillis?)` | The premium as the app knows it. |
| `setLifetimePremium(kind, startedAtMillis?)` | A lifetime premium. |
| `enableAutomaticPurchases(lifetimeProductIds: Set<String>? = null)` | The premium read from Play Billing (off by default). |

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

## 14. The server API

The SDK is a thin client of TTMailer's public SDK API. Every call carries the header `X-TTMailer-App-Key`.

| Call | Used by |
|---|---|
| `PUT /sdk/v1/installations/{installationId}` with `{pushToken, pushEnabled, language, timeZone, appVersion, osVersion, sdkVersion, userId}` | sync |
| `POST /sdk/v1/installations/{installationId}/subscribe` with `{email, language}` | `subscribe` |
| `DELETE /sdk/v1/installations/{installationId}` | `reset` |
| `DELETE /sdk/v1/installations/{installationId}/contact` | `deleteContact` |
| `POST /p/open` with `{t: ttmToken, at}` (no key) | `handle`, `opened` |
| `GET /p/m/{token}` with the header `X-TTMailer-SDK` (no key, in the WebView) | the in-app message |

Limits:
- 300 calls per IP address and minute;
- 20,000 new installations per app and day (`429`).

An unknown or disabled key answers `404`.
