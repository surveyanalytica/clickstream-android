# SurveyAnalytica Clickstream Android SDK

Lightweight Android SDK that captures behavioral events from your app and sends them to the SurveyAnalytica workflow engine for real-time customer intelligence and automation.

- **No OkHttp** — uses `java.net.HttpURLConnection`
- **No Gson / Moshi** — manual JSON serialization
- **minSdk 21** — Android 5.0+
- Thread-safe batching with `CopyOnWriteArrayList`
- Automatic flush on Activity stop (lifecycle-aware)
- Exponential-backoff retry (3 attempts)

---

## Installation

### Option A — JitPack (recommended for early access)

Add JitPack to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency in your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.surveyanalytica:clickstream-android:1.0.0")
}
```

### Option B — GitHub Packages

Add the GitHub Packages repository (requires a GitHub Personal Access Token with `read:packages` scope):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/aphougat/clickstream-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse("").get()
                password = providers.gradleProperty("gpr.token").orElse("").get()
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.surveyanalytica:clickstream-android:1.0.0")
}
```

Store credentials in `~/.gradle/gradle.properties` (never commit to source control):

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.token=ghp_YOUR_PERSONAL_ACCESS_TOKEN
```

---

## Permissions

The SDK requires only the INTERNET permission. If your app's `AndroidManifest.xml` does not already declare it, the SDK's manifest merges it automatically:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

No additional permissions are required.

---

## Quick start

### 1. Initialize in your Application class

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        SAClickstream.initialize(
            context = this,
            workflowId = "YOUR_WORKFLOW_ID",
            apiKey = "YOUR_API_KEY",
            saEndpoint = "https://your-sa-integration-url"
        )
    }
}
```

Register your Application class in `AndroidManifest.xml`:

```xml
<application
    android:name=".MyApp"
    ...>
```

### 2. Track events

```kotlin
// Simple event
SAClickstream.track("button_tapped")

// Event with properties
SAClickstream.track("purchase_completed", mapOf(
    "product_id" to "sku-1234",
    "price" to 29.99,
    "currency" to "USD"
))
```

### 3. Track screen views

Call `page()` from `Activity.onResume()` or `Fragment.onResume()`:

```kotlin
class ProductDetailActivity : AppCompatActivity() {
    override fun onResume() {
        super.onResume()
        SAClickstream.page("ProductDetailActivity", mapOf(
            "product_id" to intent.getStringExtra("product_id")
        ))
    }
}
```

### 4. Identity resolution

Call `identify()` after a user logs in to associate all prior anonymous events with their contact ID:

```kotlin
// After successful login
SAClickstream.identify(user.contactId)
```

This emits a `uid_transition` event (from the anonymous ID to the contact ID) and flushes the queue immediately.

### 5. Consent management

```kotlin
// Revoke consent — emits consent_rejected, flushes, stops all tracking
SAClickstream.setConsent(false)

// Restore consent — tracking resumes
SAClickstream.setConsent(true)
```

---

## Event payload format

Each event sent to the endpoint has the following shape:

```json
{
  "batch": [
    {
      "type": "event",
      "contactId": "anon-uuid-or-contact-id",
      "sessionId": "session-uuid",
      "event": "button_tapped",
      "properties": { "label": "Buy Now" },
      "device": {
        "platform": "android",
        "os": "android",
        "osVersion": "13",
        "device": "Google Pixel 7",
        "appVersion": "2.1.0"
      },
      "ts": "2024-06-01T12:00:00.000Z"
    }
  ]
}
```

### Special event types

| type | Emitted when |
|------|-------------|
| `event` | `track()` or `page()` is called |
| `uid_transition` | `identify()` is called with a new ID |
| `consent_rejected` | `setConsent(false)` is called |

---

## Batching and delivery

- Events are buffered in memory using a `CopyOnWriteArrayList`.
- The queue flushes automatically when **20 events** are queued OR after a **500 ms debounce**.
- The SDK also flushes in `onActivityStopped` via `Application.ActivityLifecycleCallbacks`.
- Failed requests are retried up to **3 times** with exponential backoff (1 s, 2 s, 4 s).
- After 3 failed attempts the batch is silently dropped to avoid blocking the app.

---

## Building locally

```bash
git clone https://github.com/aphougat/clickstream-android.git
cd clickstream-android
./gradlew :library:assembleRelease
```

Run unit tests:

```bash
./gradlew :library:test
```

Publish to local Maven repository:

```bash
./gradlew :library:publishReleasePublicationToLocalMavenRepository
# Output: build/repo/com/surveyanalytica/clickstream-android/1.0.0/
```

---

## License

MIT License. See [LICENSE](LICENSE) for details.
