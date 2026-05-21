package com.surveyanalytica.clickstream

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SurveyAnalytica Clickstream Android SDK.
 *
 * Captures behavioral events from Android applications and sends them in batches
 * to the SurveyAnalytica workflow engine.
 *
 * ## Usage
 *
 * ```kotlin
 * // In Application.onCreate()
 * SAClickstream.initialize(
 *     context = this,
 *     workflowId = "YOUR_WORKFLOW_ID",
 *     apiKey = "YOUR_API_KEY",
 *     saEndpoint = "https://your-sa-integration-url"
 * )
 *
 * // Track an event
 * SAClickstream.track("button_tapped", mapOf("label" to "Buy Now"))
 *
 * // Track a screen view (call in Activity.onResume / Fragment.onResume)
 * SAClickstream.page("ProductDetailActivity")
 *
 * // Associate a known contact ID
 * SAClickstream.identify("user-123")
 *
 * // Revoke consent — stops all tracking
 * SAClickstream.setConsent(false)
 * ```
 */
object SAClickstream {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private const val TAG = "SAClickstream"
    private const val PREFS_NAME = "sa_clickstream"
    private const val PREFS_KEY_SA_ID = "sa_id"
    private const val MAX_BATCH_SIZE = 20
    private const val FLUSH_DEBOUNCE_MS = 500L
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    // -------------------------------------------------------------------------
    // State — all access is protected by the handler's single-thread ordering
    // or CopyOnWriteArrayList for the queue.
    // -------------------------------------------------------------------------

    @Volatile private var workflowId: String = ""
    @Volatile private var apiKey: String = ""
    @Volatile private var saEndpoint: String = ""
    @Volatile private var contactId: String = ""
    @Volatile private var sessionId: String = ""
    @Volatile private var consentGiven: Boolean = true
    @Volatile private var initialized: Boolean = false

    private val queue = CopyOnWriteArrayList<SAClickstreamEvent>()
    private lateinit var deviceInfo: SADeviceInfo
    private lateinit var prefs: SharedPreferences

    /** Main-thread handler used for debounced flush scheduling. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Coroutine scope for background HTTP work. SupervisorJob so one failed send
     *  does not cancel the entire scope. */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val flushRunnable = Runnable { flush() }

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    /**
     * Initialize the SDK. Must be called once from [Application.onCreate] before
     * any tracking calls.
     *
     * @param context Application context.
     * @param workflowId The SurveyAnalytica workflow ID for this integration.
     * @param apiKey The API key sent as the `code` request header.
     * @param saEndpoint Base URL of the SAIntegrationService (no trailing slash required).
     */
    fun initialize(
        context: Context,
        workflowId: String,
        apiKey: String,
        saEndpoint: String,
    ) {
        if (workflowId.isBlank()) {
            Log.e(TAG, "workflowId must not be blank — SDK not initialized.")
            return
        }
        if (apiKey.isBlank()) {
            Log.e(TAG, "apiKey must not be blank — SDK not initialized.")
            return
        }
        if (saEndpoint.isBlank()) {
            Log.e(TAG, "saEndpoint must not be blank — SDK not initialized.")
            return
        }

        if (initialized) {
            Log.w(TAG, "SAClickstream.initialize() called more than once — ignoring.")
            return
        }

        this.workflowId = workflowId
        this.apiKey = apiKey
        this.saEndpoint = saEndpoint.trimEnd('/')
        this.sessionId = UUID.randomUUID().toString()

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        contactId = resolveOrCreateContactId()

        deviceInfo = buildDeviceInfo(context)

        registerLifecycleCallbacks(context)

        initialized = true

        Log.d(TAG, "SAClickstream initialized. contactId=$contactId sessionId=$sessionId")
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Track a named event with optional properties.
     *
     * @param eventName Name of the event (e.g. "button_tapped", "purchase_completed").
     * @param properties Optional key-value metadata. Values must be String, Number,
     *   Boolean, List, Map, or null.
     */
    fun track(eventName: String, properties: Map<String, Any> = emptyMap()) {
        if (!assertInitialized("track")) return
        if (!consentGiven) return
        if (eventName.isBlank()) {
            Log.w(TAG, "track() called with blank eventName — ignoring.")
            return
        }
        enqueue(buildEvent(type = "event", event = eventName, properties = properties))
    }

    /**
     * Track a screen view. Call this from [Activity.onResume] or [Fragment.onResume].
     *
     * @param screenName Name of the screen or activity (e.g. "ProductDetailActivity").
     * @param properties Optional additional properties merged into the page_view event.
     */
    fun page(screenName: String, properties: Map<String, Any> = emptyMap()) {
        if (!assertInitialized("page")) return
        if (!consentGiven) return
        if (screenName.isBlank()) {
            Log.w(TAG, "page() called with blank screenName — ignoring.")
            return
        }
        val props = buildMap {
            put("screen", screenName)
            putAll(properties)
        }
        enqueue(buildEvent(type = "event", event = "page_view", properties = props))
    }

    /**
     * Associate the current session with a known contact ID (e.g. after login).
     *
     * Emits a [uid_transition] event from the previously anonymous ID to [contactId],
     * flushes the pending queue immediately so prior events are attributed to the
     * old ID before the transition is recorded.
     *
     * @param newContactId The resolved contact ID from your backend.
     */
    fun identify(newContactId: String) {
        if (!assertInitialized("identify")) return
        if (!consentGiven) return
        if (newContactId.isBlank()) {
            Log.w(TAG, "identify() called with blank contactId — ignoring.")
            return
        }

        val oldId = contactId
        if (oldId == newContactId) return

        // Flush events attributed to the old ID before recording the transition.
        flush()

        contactId = newContactId
        prefs.edit().putString(PREFS_KEY_SA_ID, newContactId).apply()

        // Emit uid_transition as an immediate standalone batch.
        val transitionEvent = SAClickstreamEvent(
            type = "uid_transition",
            contactId = newContactId,
            sessionId = sessionId,
            event = "",
            properties = emptyMap(),
            device = deviceInfo,
            ts = isoNow(),
            oldId = oldId,
            newId = newContactId,
        )
        sendImmediately(listOf(transitionEvent))

        Log.d(TAG, "Identity resolved: $oldId -> $newContactId")
    }

    /**
     * Set the user's consent state.
     *
     * When consent is revoked ([given] = false):
     * - A [consent_rejected] event is emitted and flushed immediately.
     * - The pending queue is cleared.
     * - All subsequent [track], [page], and [identify] calls are silently ignored.
     *
     * When consent is restored ([given] = true), tracking resumes normally.
     *
     * @param given `true` to grant consent, `false` to revoke it.
     */
    fun setConsent(given: Boolean) {
        if (!given) {
            // Emit consent_rejected before stopping — best-effort delivery.
            if (initialized) {
                val rejectEvent = buildEvent(
                    type = "consent_rejected",
                    event = "consent_rejected",
                    properties = emptyMap(),
                )
                sendImmediately(listOf(rejectEvent))
            }
            consentGiven = false
            cancelPendingFlush()
            queue.clear()
            Log.d(TAG, "Consent revoked — tracking stopped.")
        } else {
            consentGiven = true
            Log.d(TAG, "Consent granted — tracking resumed.")
        }
    }

    // -------------------------------------------------------------------------
    // Internal — event building and queuing
    // -------------------------------------------------------------------------

    private fun buildEvent(
        type: String,
        event: String,
        properties: Map<String, Any>,
    ): SAClickstreamEvent = SAClickstreamEvent(
        type = type,
        contactId = contactId,
        sessionId = sessionId,
        event = event,
        properties = properties,
        device = deviceInfo,
        ts = isoNow(),
    )

    private fun enqueue(event: SAClickstreamEvent) {
        queue.add(event)
        if (queue.size >= MAX_BATCH_SIZE) {
            cancelPendingFlush()
            flush()
        } else {
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        mainHandler.removeCallbacks(flushRunnable)
        mainHandler.postDelayed(flushRunnable, FLUSH_DEBOUNCE_MS)
    }

    private fun cancelPendingFlush() {
        mainHandler.removeCallbacks(flushRunnable)
    }

    // -------------------------------------------------------------------------
    // Internal — flushing and HTTP
    // -------------------------------------------------------------------------

    /**
     * Drains the current queue and sends all pending events as a single batch.
     * Called from the main thread (via handler) and from onActivityStopped.
     */
    internal fun flush() {
        cancelPendingFlush()
        if (queue.isEmpty()) return

        // Snapshot and clear atomically using CopyOnWriteArrayList.
        // toList() copies the current state; then removeAll removes those exact objects.
        val batch = queue.toList()
        queue.removeAll(batch.toSet())

        if (batch.isEmpty()) return

        sendBatch(batch)
    }

    /**
     * Sends [events] immediately as a standalone batch, bypassing the queue.
     * Used for uid_transition and consent_rejected events.
     */
    private fun sendImmediately(events: List<SAClickstreamEvent>) {
        if (events.isEmpty()) return
        sendBatch(events)
    }

    /**
     * Launches a coroutine to POST [events] to the clickstream endpoint.
     * Retries up to [MAX_RETRY_ATTEMPTS] times with exponential backoff.
     */
    private fun sendBatch(events: List<SAClickstreamEvent>) {
        val endpoint = buildEndpointUrl()
        val body = buildBatchJson(events)

        ioScope.launch {
            sendWithRetry(endpoint, body, attempt = 1)
        }
    }

    private suspend fun sendWithRetry(url: String, body: String, attempt: Int) {
        val success = withContext(Dispatchers.IO) {
            doHttpPost(url, body)
        }

        if (success) {
            Log.d(TAG, "Batch sent successfully (attempt $attempt).")
            return
        }

        if (attempt >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Batch failed after $MAX_RETRY_ATTEMPTS attempts — dropping.")
            return
        }

        val backoffMs = (Math.pow(2.0, (attempt - 1).toDouble()) * 1000L).toLong()
        Log.d(TAG, "Retry $attempt failed — backing off ${backoffMs}ms before attempt ${attempt + 1}.")
        delay(backoffMs)
        sendWithRetry(url, body, attempt + 1)
    }

    /**
     * Executes the HTTP POST using [HttpURLConnection] (no OkHttp dependency).
     *
     * @return `true` if the server responded with HTTP 2xx.
     */
    private fun doHttpPost(url: String, body: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("code", apiKey)
                setRequestProperty("Accept", "application/json")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
            }

            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            connection.setRequestProperty("Content-Length", bodyBytes.size.toString())

            connection.outputStream.use { os: OutputStream ->
                os.write(bodyBytes)
                os.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                Log.w(TAG, "Server returned HTTP $responseCode for batch POST.")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP POST failed: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    // -------------------------------------------------------------------------
    // Internal — JSON building
    // -------------------------------------------------------------------------

    private fun buildBatchJson(events: List<SAClickstreamEvent>): String {
        val sb = StringBuilder()
        sb.append("{\"batch\":[")
        events.forEachIndexed { index, event ->
            sb.append(event.toJson())
            if (index < events.size - 1) sb.append(',')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun buildEndpointUrl(): String =
        "$saEndpoint/api/v1/clickstream/$workflowId"

    // -------------------------------------------------------------------------
    // Internal — identity resolution
    // -------------------------------------------------------------------------

    private fun resolveOrCreateContactId(): String {
        val stored = prefs.getString(PREFS_KEY_SA_ID, null)
        if (!stored.isNullOrBlank()) return stored

        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(PREFS_KEY_SA_ID, generated).apply()
        return generated
    }

    // -------------------------------------------------------------------------
    // Internal — device info
    // -------------------------------------------------------------------------

    private fun buildDeviceInfo(context: Context): SADeviceInfo {
        val osVersion = Build.VERSION.RELEASE
        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val appVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        return SADeviceInfo(
            os = "android",
            osVersion = osVersion,
            device = deviceLabel,
            appVersion = appVersion,
        )
    }

    // -------------------------------------------------------------------------
    // Internal — lifecycle registration
    // -------------------------------------------------------------------------

    /**
     * Registers [Application.ActivityLifecycleCallbacks] so the SDK flushes the
     * pending event queue whenever any Activity is stopped (i.e. goes background).
     * This provides a best-effort delivery guarantee without draining the battery.
     */
    private fun registerLifecycleCallbacks(context: Context) {
        val app = context.applicationContext as? Application ?: return

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                // Flush when the activity goes off-screen. If another activity
                // is coming to the foreground (navigation), the flush is a no-op
                // (queue was already empty or will be processed shortly).
                flush()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    // -------------------------------------------------------------------------
    // Internal — utilities
    // -------------------------------------------------------------------------

    private fun assertInitialized(caller: String): Boolean {
        if (!initialized) {
            Log.e(TAG, "$caller() called before SAClickstream.initialize() — call ignored.")
            return false
        }
        return true
    }

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    // -------------------------------------------------------------------------
    // Test helpers — package-private to support unit tests
    // -------------------------------------------------------------------------

    /**
     * Resets the singleton to uninitialized state. For use in unit tests only.
     * Not part of the public API.
     */
    internal fun reset() {
        cancelPendingFlush()
        queue.clear()
        workflowId = ""
        apiKey = ""
        saEndpoint = ""
        contactId = ""
        sessionId = ""
        consentGiven = true
        initialized = false
    }

    /**
     * Returns the current queue size. For use in unit tests only.
     */
    internal fun queueSize(): Int = queue.size
}
