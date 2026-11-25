# TTMailer SDK for Android

For apps without their own backend: registers the installation and its FCM token in TTMailer, signs the user up to the
newsletter (TTMailer sends a confirmation e-mail), links the app's account, reports opened pushes and does their tap action (browser, Custom Tabs or an in-app message).
Kotlin, one direct dependency (`androidx.browser` for Custom Tabs, with a few small AndroidX libraries), Android 7+
(minSdk 24); the app needs AndroidX and compileSdk 34+.

**📖 Full guide: [docs/integration.md](docs/integration.md)**: the setup in TTMailer, push, accounts, the newsletter,
privacy (Data safety), troubleshooting and the API reference.

## Install

Through JitPack (built from the tags of https://github.com/tappytaps/ttmailer-sdk-android):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } } }
// app/build.gradle.kts
dependencies { implementation("com.github.tappytaps:ttmailer-sdk-android:0.4.0") }
```

## Use

The app key comes from TTMailer: Nastavení → Aplikace (SDK). It is public (it ships in the app).

```kotlin
// Application.onCreate
TTMailer.configure(this, appKey = "tta_…")

// FirebaseMessagingService
override fun onNewToken(token: String) = TTMailer.setPushToken(token)
// and once at start: FirebaseMessaging.getInstance().token.addOnSuccessListener { TTMailer.setPushToken(it) }

// The app's notification switch, or the system setting:
TTMailer.setPushEnabled(NotificationManagerCompat.from(context).areNotificationsEnabled())

// Signed in / signed out (only for apps with accounts; use an unguessable id such as an account UUID):
TTMailer.setUserId(account.id)
TTMailer.setUserId(null)

// Newsletter sign-up (TTMailer sends a confirmation e-mail in the device's language):
lifecycleScope.launch {
    try {
        TTMailer.subscribe(email)
        // show "Check your inbox"
    } catch (e: TTMailerException) {
        // e.reason: INVALID_EMAIL, RATE_LIMITED, NETWORK, …
    }
}
// or with a callback: TTMailer.subscribe(email) { result -> … }

// A tap on a notification: the data of the push (in the launch intent's extras when the app was in the background,
// or RemoteMessage.data when you build the notification yourself):
// handle() reports the open and does the push's action (TTMailer: Po klepnutí): the browser, Custom Tabs or an in-app
// message (it then returns null); for a plain push it returns the link (deep link or web) for the app to open.
// Call it once per tap on the main thread (onCreate with savedInstanceState == null, and onNewIntent), from the
// activity that stays (not a splash/trampoline that finishes).
TTMailer.handle(this, intent.extras)?.let { link -> Router.open(this, Uri.parse(link)) }
// The app's own links from an in-app message (set it in Application.onCreate; without it, the system opens them):
TTMailer.onDeepLink = { link -> Router.open(currentActivity, Uri.parse(link)) }
// TTMailer.opened(intent.extras) still works (the open and the link), but ignores the action.

// The user deleted their account (GDPR):
TTMailer.deleteContact()   // suspend; or deleteContact { result -> … }

// The app's own language, tags for audiences:
TTMailer.setLanguage("de")
TTMailer.addTags(listOf("sportovec"))

// Premium: read from Play Billing (off by default; the app's own Play Billing 7+) …
TTMailer.enableAutomaticPurchases()
// … or reported by the app (e.g. from RevenueCat):
TTMailer.setPremium(untilMillis = expiration, kind = "paid")
```

### What the SDK sends

- On start, on every change (token, switch, user id) and when an activity resumes (at most once a day when nothing
  changed): the installation id (a random UUID kept in the app), the push token, the switch, the user id, the device
  language, time zone, app version, Android version and the SDK version.
- Opens: the push's `ttmToken`, with the time of the tap. Kept while offline and sent later.
- An in-app message loads TTMailer's page with the header `X-TTMailer-SDK: <version>`.
- Failed calls are retried with a growing pause while the app runs, and again on the next start.

`TTMailer.reset()` removes this installation from TTMailer and starts a new one.

## Develop

```sh
./gradlew :ttmailer:testDebugUnitTest
./gradlew :ttmailer:publishReleasePublicationToLocalRepository   # build/repo
```
