package com.runanywhere.kotlin_starter_example.services

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.runanywhere.kotlin_starter_example.BuildConfig
import com.runanywhere.kotlin_starter_example.data.SettingsRepository
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * ElevenLabsService - Robust integration for TTS and Realtime STT.
 */
object ElevenLabsService {
    private const val TAG = "ElevenLabsService"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Updated Voice ID shared by user: TYKLc7ViOIGE13dSZYlK
    private const val TARGET_VOICE_ID = "EXAVITQu4vr4xnSDxMaL"
    private const val AGENT_ID = "agent_1701m2wtpwzzej79rtjhxmhf7cvj"
    
    private const val SCRIBE_MODEL_ID = "scribe_v2_realtime"
    private const val SCRIBE_HOST = "api.elevenlabs.io"

    private val MASTER_API_KEY: String
        get() {
            val rawKey = try { BuildConfig.ELEVENLABS_API_KEY } catch (e: Exception) { "placeholder" }
            return rawKey.replace("\"", "").replace("'", "").trim()
        }

    fun hasValidKey(): Boolean {
        val apiKey = MASTER_API_KEY
        return apiKey.isNotBlank() && apiKey != "placeholder"
    }

    fun isEnabled(context: Context): Boolean {
        val keyValid = hasValidKey()
        val networkAvailable = isNetworkAvailable(context)
        val userEnabled = SettingsRepository.useElevenLabs.value
        
        if (!keyValid) Log.w(TAG, "ElevenLabs disabled: API Key is 'placeholder' or blank. Add 'elevenlabs.api.key=YOUR_KEY' to local.properties")
        if (!networkAvailable) Log.w(TAG, "ElevenLabs disabled: No internet connection")
        if (!userEnabled) Log.d(TAG, "ElevenLabs disabled: User preference is off")
        
        return userEnabled && keyValid && networkAvailable
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun createScribeWebSocket(
        onTranscriptResult: (String, Boolean) -> Unit,
        onError: (String, Boolean) -> Unit
    ): WebSocket? {
        val apiKey = MASTER_API_KEY
        if (!hasValidKey()) return null

        val url = HttpUrl.Builder()
            .scheme("https")
            .host(SCRIBE_HOST)
            .addPathSegment("v1")
            .addPathSegment("speech-to-text")
            .addPathSegment("realtime")
            .addQueryParameter("model_id", SCRIBE_MODEL_ID)
            .addQueryParameter("audio_format", "pcm_16000")
            .addQueryParameter("language_code", "en")
            .addQueryParameter("commit_strategy", "vad")
            .build()
            .toString()
            .replace("https://", "wss://")

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .build()

        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Scribe Handshake Success")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("message_type", "")
                    if (type == "partial_transcript" || type == "committed_transcript") {
                        onTranscriptResult(json.optString("text", ""), type == "committed_transcript")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse Error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                onError("Scribe Error ($code)", code == 403 || code == 401)
            }
        })
    }

    fun sendScribeAudio(webSocket: WebSocket, audio: ByteArray): Boolean {
        return try {
            val json = JSONObject().apply {
                put("message_type", "input_audio_chunk")
                put("audio_base_64", Base64.encodeToString(audio, Base64.NO_WRAP))
                put("commit", false) 
                put("sample_rate", 16000)
            }
            webSocket.send(json.toString())
        } catch (e: Exception) {
            false
        }
    }

    /**
     * TTS using /stream endpoint with model_id fallback.
     * Updated to use voice id: TYKLc7ViOIGE13dSZYlK
     */
    suspend fun textToSpeech(context: Context, text: String): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = MASTER_API_KEY
        val keyConfigured = hasValidKey()
        val enabled = isEnabled(context)
        fun redact(message: String): String =
            if (apiKey.isNotBlank()) message.replace(apiKey, "[REDACTED]") else message

        Log.d(TAG, "TTS request: isEnabled=$enabled, apiKeyConfigured=$keyConfigured")
        if (!keyConfigured) {
            val reason = "ElevenLabs API key is not configured"
            Log.e(TAG, "TTS failure: httpStatus=not_received, isEnabled=$enabled, apiKeyConfigured=$keyConfigured, reason=$reason")
            throw IllegalStateException(reason)
        }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.elevenlabs.io")
            .addPathSegment("v1")
            .addPathSegment("text-to-speech")
            .addPathSegment(TARGET_VOICE_ID)
            .addPathSegment("stream")
            .addQueryParameter("optimize_streaming_latency", "3")
            .addQueryParameter("output_format", "mp3_44100_128")
            .build()

        val json = JSONObject().apply {
            put("text", text)
            // Using eleven_flash_v2_5 for ultra-low latency conversational performance
            put("model_id", "eleven_flash_v2_5")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("use_speaker_boost", true)
            })
        }

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .post(body)
            .build()

        var httpStatus: Int? = null
        try {
            client.newCall(request).execute().use { response ->
                httpStatus = response.code
                if (response.isSuccessful) {
                    val audio = response.body?.bytes()
                    if (audio == null || audio.isEmpty()) {
                        throw IllegalStateException("ElevenLabs returned an empty audio body")
                    }
                    Log.d(TAG, "TTS response: httpStatus=${response.code}, audioBytes=${audio.size}")
                    audio
                } else {
                    val errBody = redact(response.body?.string().orEmpty())
                    withContext(Dispatchers.Main) {
                        if (response.code == 401) Toast.makeText(context, "ElevenLabs: Invalid API Key", Toast.LENGTH_LONG).show()
                        else if (response.code == 429) Toast.makeText(context, "ElevenLabs: Quota Exceeded", Toast.LENGTH_LONG).show()
                        else Toast.makeText(context, "ElevenLabs: Request failed (${response.code})", Toast.LENGTH_SHORT).show()
                    }
                    throw IllegalStateException("ElevenLabs HTTP ${response.code}: ${errBody.ifBlank { "Empty error response body" }}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = redact(e.message ?: e.javaClass.simpleName)
            Log.e(TAG, "TTS failure: httpStatus=${httpStatus ?: "not_received"}, isEnabled=$enabled, apiKeyConfigured=$keyConfigured, reason=$reason")
            throw IllegalStateException(reason)
        }
    }

    /**
     * Play MP3 bytes.
     */
    suspend fun playMp3Bytes(context: Context, audioBytes: ByteArray) {
        val tempFile = withContext(Dispatchers.IO) {
            try {
                val file = File.createTempFile("eleven_tts", ".mp3", context.cacheDir)
                file.deleteOnExit()
                FileOutputStream(file).use { it.write(audioBytes) }
                file
            } catch (e: Exception) {
                null
            }
        } ?: return

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                var mediaPlayer: MediaPlayer? = null
                try {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener {
                            it.release()
                            tempFile.delete()
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        setOnErrorListener { mp, _, _ ->
                            mp.release()
                            tempFile.delete()
                            if (continuation.isActive) continuation.resume(Unit)
                            true
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    if (continuation.isActive) continuation.resume(Unit)
                }
                
                continuation.invokeOnCancellation {
                    mediaPlayer?.release()
                    tempFile.delete()
                }
            }
        }
    }

    suspend fun generateCloudSuggestions(history: List<Pair<String, Boolean>>): List<String>? = withContext(Dispatchers.IO) {
        val apiKey = MASTER_API_KEY
        if (!hasValidKey()) return@withContext null
        try {
            val contextText = history.takeLast(10).joinToString("\n") { (text, isOther) ->
                if (isOther) "User: $text" else "Me: $text"
            }
            val prompt = "Suggest 3 short natural replies for Me.\nHistory: $contextText\nFormat: JSON array of strings ONLY."
            val body = RequestBody.create("application/json".toMediaTypeOrNull(), JSONObject().apply { put("text", prompt) }.toString())
            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/convai/agents/$AGENT_ID/completion")
                .addHeader("xi-api-key", apiKey)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val replyText = JSONObject(response.body?.string() ?: "").optString("text", "")
                    "\"([^\"]*)\"".toRegex().findAll(replyText).map { it.groupValues[1] }.take(3).toList()
                } else null
            }
        } catch (e: Exception) { null }
    }
}
