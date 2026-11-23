# TTMailer SDK for Android

For apps without their own backend: registers the installation and its FCM token in TTMailer, signs the user up to the
newsletter (TTMailer sends a confirmation e-mail), links the app's account and reports opened pushes. Kotlin, no
dependencies, Android 7+ (minSdk 24). The server API is described in `docs/sdk-api.md` of the TTMailer repository.

## Install

Through JitPack (built from the tags of https://github.com/tappytaps/ttmailer-sdk-android):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } } }
// app/build.gradle.kts
dependencies { implementation("com.github.tappytaps:ttmailer-sdk-android:0.1.0") }
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
val link = TTMailer.opened(intent.extras)   // the push's link, if any (deep link or web)

// The user deleted their account (GDPR):
TTMailer.deleteContact()   // suspend; or deleteContact { result -> … }
```

### What the SDK sends

- On start, on every change (token, switch, user id) and when an activity resumes (at most once a day when nothing
  changed): the installation id (a random UUID kept in the app), the push token, the switch, the user id, the device
  language, time zone, app version and Android version.
- Opens: the push's `ttmToken`, with the time of the tap. Kept while offline and sent later.
- Failed calls are retried with a growing pause while the app runs, and again on the next start.

`TTMailer.reset()` removes this installation from TTMailer and starts a new one.

## Develop

```sh
./gradlew :ttmailer:testDebugUnitTest
./gradlew :ttmailer:publishReleasePublicationToLocalRepository   # build/repo
```
