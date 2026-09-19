package com.runanywhere.kotlin_starter_example.services

import android.content.Context
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import com.runanywhere.kotlin_starter_example.BuildConfig
import com.runanywhere.kotlin_starter_example.data.SettingsRepository
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ElevenLabsService - Robust integration for TTS and Scribe v2 Realtime STT.
 */
object ElevenLabsService {
    private const val TAG = "ElevenLabsService"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val TARGET_VOICE_ID = "TYKLc7ViOIGE13dSZYlK"
    private const val AGENT_ID = "agent_1701m2wtpwzzej79rtjhxmhf7cvj"
    
    private const val SCRIBE_MODEL_ID = "scribe_v2_realtime"
    private const val SCRIBE_HOST = "api.elevenlabs.io"

    private val MASTER_API_KEY: String
        get() {
            val rawKey = try { BuildConfig.ELEVENLABS_API_KEY } catch (e: Exception) { "placeholder" }
            return rawKey.replace("\"", "").replace("'", "").trim()
        }

    fun isEnabled(context: Context): Boolean {
        val apiKey = MASTER_API_KEY
        return SettingsRepository.useElevenLabs.value && 
               apiKey.isNotBlank() && apiKey != "placeholder" && 
               isNetworkAvailable(context)
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * WebSocket for Scribe v2 Realtime.
     */
    fun createScribeWebSocket(
        onTranscriptResult: (String, Boolean) -> Unit,
        onError: (String, Boolean) -> Unit
    ): WebSocket? {
        val apiKey = MASTER_API_KEY
        if (apiKey.isBlank() || apiKey == "placeholder") return null

        // Official Handshake: configuration in query params
        val url = HttpUrl.Builder()
            .scheme("https") // Builder requires https/http
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

        Log.d(TAG, "Connecting Scribe: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .build()

        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Scribe v2 Realtime WebSocket Open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("message_type", "")
                    
                    when (type) {
                        "partial_transcript", "committed_transcript" -> {
                            val transcript = json.optString("text", "")
                            val isFinal = (type == "committed_transcript")
                            if (transcript.isNotEmpty()) {
                                onTranscriptResult(transcript, isFinal)
                            }
                        }
                        "session_started" -> {
                            Log.d(TAG, "Scribe Session Started: ${json.optString("session_id")}")
                        }
                        "error", "auth_error", "quota_exceeded" -> {
                            val msg = json.optString("error", "Unknown error")
                            Log.e(TAG, "Scribe Server Error: $msg")
                            onError(msg, type == "auth_error")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Msg Parse Error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val msg = when(code) {
                    401 -> "Invalid API Key."
                    403 -> "Forbidden: Ensure 'Scribe' is enabled in ElevenLabs dashboard."
                    429 -> "Quota Exceeded."
                    else -> t.message ?: "Connection Failure"
                }
                Log.e(TAG, "Scribe WebSocket Failure ($code): $msg")
                onError(msg, code == 403 || code == 401)
            }
        })
    }

    /**
     * Official Scribe v2 Audio Framing.
     */
    fun sendScribeAudio(webSocket: WebSocket, audio: ByteArray): Boolean {
        return try {
            val json = JSONObject().apply {
                put("message_type", "input_audio_chunk")
                put("audio_base_64", Base64.encodeToString(audio, Base64.NO_WRAP))
                put("commit", false) 
                put("sample_rate", 16000)
            }
            // OkHttp send() returns false if the socket is not OPEN
            webSocket.send(json.toString())
        } catch (e: Exception) {
            false
        }
    }

    suspend fun textToSpeech(text: String): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = MASTER_API_KEY
        if (apiKey.isBlank() || apiKey == "placeholder") return@withContext null

        val json = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_flash_v2_5")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
            })
        }

        val body = RequestBody.create("application/json".toMediaTypeOrNull(), json.toString())
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$TARGET_VOICE_ID")
            .addHeader("xi-api-key", apiKey)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.bytes()
                } else {
                    val err = response.body?.string()
                    Log.e(TAG, "TTS Failed: ${response.code} - $err")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS Net Error: ${e.message}")
            null
        }
    }

    suspend fun generateCloudSuggestions(history: List<Pair<String, Boolean>>): List<String>? = withContext(Dispatchers.IO) {
        val apiKey = MASTER_API_KEY
        if (apiKey.isBlank() || apiKey == "placeholder") return@withContext null
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

    fun playMp3Bytes(context: Context, audioBytes: ByteArray, onComplete: () -> Unit = {}) {
        try {
            val tempFile = File.createTempFile("eleven_tts", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }
            MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                start()
                setOnCompletionListener { it.release(); tempFile.delete(); onComplete() }
                setOnErrorListener { mp, _, _ -> mp.release(); onComplete(); true }
            }
        } catch (e: Exception) { onComplete() }
    }
}
