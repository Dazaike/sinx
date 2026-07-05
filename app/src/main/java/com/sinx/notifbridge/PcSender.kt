package com.sinx.notifbridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends notification payloads to the Windows PC over a local HTTP POST.
 *
 * A single shared [OkHttpClient] is reused for connection pooling.
 * Each send is dispatched on [Dispatchers.IO] via a [SupervisorJob] scope so a
 * failed delivery never brings down the caller.
 */
class PcSender(private val settings: SettingsManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = "application/json".toMediaType()

    data class Payload(
        val app: String,
        val title: String,
        val text: String,
        val packageName: String
    )

    /**
     * Fire-and-forget send. [onResult] is invoked on the **main thread** when the
     * delivery completes or fails — pass non-null only when you need feedback (e.g. test button).
     */
    fun send(
        payload: Payload,
        onResult: ((success: Boolean, error: String?) -> Unit)? = null
    ) {
        if (!settings.isEnabled) {
            onResult?.invoke(false, "Forwarding is disabled")
            return
        }
        dispatch(payload, onResult)
    }

    /** Same as [send] but bypasses the isEnabled check — used by the test button. */
    fun sendTest(payload: Payload, onResult: (success: Boolean, error: String?) -> Unit) {
        dispatch(payload, onResult)
    }

    private fun dispatch(
        payload: Payload,
        onResult: ((success: Boolean, error: String?) -> Unit)?
    ) {
        scope.launch {
            var success = false
            var errorMsg: String? = null
            try {
                val body = JSONObject().apply {
                    put("app", payload.app)
                    put("title", payload.title)
                    put("text", payload.text)
                    put("pkg", payload.packageName)
                }.toString().toRequestBody(json)

                val request = Request.Builder()
                    .url(settings.endpointUrl)
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        success = true
                    } else {
                        errorMsg = "Server returned ${response.code}"
                        Log.w(TAG, "Server replied ${response.code} for ${payload.app}")
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message
                Log.d(TAG, "Delivery failed: ${e.message}")
            }
            // Always call back on main so callers can touch the UI safely
            if (onResult != null) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    onResult(success, errorMsg)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PcSender"
    }
}
