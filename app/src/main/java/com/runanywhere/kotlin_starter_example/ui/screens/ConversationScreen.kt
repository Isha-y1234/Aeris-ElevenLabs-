package com.runanywhere.kotlin_starter_example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import com.runanywhere.kotlin_starter_example.BuildConfig
import com.runanywhere.kotlin_starter_example.data.HistoryContentLine
import com.runanywhere.kotlin_starter_example.data.HistoryType
import com.runanywhere.kotlin_starter_example.services.AudioForegroundService
import com.runanywhere.kotlin_starter_example.services.ElevenLabsService
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.services.playWavBytes
import com.runanywhere.kotlin_starter_example.viewmodel.HistoryViewModel
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.TTS.TTSOptions
import com.runanywhere.sdk.public.extensions.chat
import com.runanywhere.sdk.public.extensions.synthesize
import com.runanywhere.sdk.public.extensions.transcribe
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

// ── Data models ──────────────────────────────────────────────────────────────

data class ConversationMessage(
    val text: String,
    val isFromOther: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ConversationState {
    IDLE,
    LISTENING,
    TRANSCRIBING,
    GENERATING_SUGGESTIONS,
    GENERATING_SPEECH,
    SPEAKING
}

@Composable
fun ConversationScreen(
    modelService: ModelService,
    historyViewModel: HistoryViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var messages by remember { mutableStateOf(listOf<ConversationMessage>()) }
    var myReply by remember { mutableStateOf("") }
    var conversationState by remember { mutableStateOf(ConversationState.IDLE) }
    var listenJob by remember { mutableStateOf<Job?>(null) }
    var hasPermission by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(listOf<String>()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    var currentSessionId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var streamingTranscript by remember { mutableStateOf("") }

    val softBlue = Color(0xFF6FB1FC)
    val purple   = Color(0xFF9C6FFC)
    val red      = Color(0xFFFF6B6B)

    fun autoSaveHistory() {
        if (messages.isEmpty()) return
        val content = messages.map { HistoryContentLine(it.text, it.isFromOther, it.timestamp) }
        historyViewModel.saveSession(HistoryType.CONVERSATION, content, currentSessionId)
    }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        historyViewModel.loadHistory(HistoryType.CONVERSATION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(messages.size, streamingTranscript) {
        if (messages.isNotEmpty() || streamingTranscript.isNotEmpty()) {
            val targetIndex = if (streamingTranscript.isNotEmpty()) messages.size else messages.size - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    val llmReady = modelService.isLLMLoaded
    val sttReady = modelService.isSTTLoaded
    val ttsReady = modelService.isTTSLoaded
    val elevenLabsEnabled = ElevenLabsService.isEnabled(context)

    fun generateSuggestions() {
        scope.launch {
            conversationState = ConversationState.GENERATING_SUGGESTIONS
            var cloudReplies: List<String>? = null
            
            if (ElevenLabsService.isEnabled(context)) {
                val historyPairs = messages.map { it.text to it.isFromOther }
                cloudReplies = ElevenLabsService.generateCloudSuggestions(historyPairs)
            }

            if (cloudReplies != null && cloudReplies.isNotEmpty()) {
                suggestions = cloudReplies
                conversationState = ConversationState.IDLE
            } else {
                if (llmReady) {
                    try {
                        val contextPrompt = buildString {
                            appendLine("You are helping a deaf person reply in a conversation.")
                            appendLine("Recent conversation:")
                            messages.takeLast(4).forEach { msg ->
                                appendLine(if (msg.isFromOther) "Other: ${msg.text}" else "Me: ${msg.text}")
                            }
                            appendLine("\nSuggest exactly 3 short natural replies (max 8 words each).")
                            appendLine("Format: one reply per line, no labels.")
                        }
                        val response = withContext(Dispatchers.IO) { RunAnywhere.chat(contextPrompt) }
                        suggestions = response.trim().split("\n")
                            .map { it.trim().removePrefix("-").trim() }
                            .filter { it.isNotBlank() }
                            .take(3)
                    } catch (e: Exception) {
                        suggestions = listOf("I understand", "Can you repeat?", "One moment")
                    } finally {
                        conversationState = ConversationState.IDLE
                    }
                } else {
                    conversationState = ConversationState.IDLE
                }
            }
        }
    }

    fun listenForOther() {
        conversationState = ConversationState.LISTENING
        errorMessage = null
        suggestions = emptyList()
        streamingTranscript = ""

        // Pause background mic classification to avoid hardware conflict
        AudioForegroundService.stopMicCapture()

        listenJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

            try {
                val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4)
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) { 
                        errorMessage = "Microphone unavailable"
                        conversationState = ConversationState.IDLE 
                    }
                    AudioForegroundService.startMicCapture()
                    return@launch
                }
                record.startRecording()

                var sttFallbackTriggered = false

                if (ElevenLabsService.isEnabled(context)) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Co-pilot (ElevenLabs) Active", Toast.LENGTH_SHORT).show() }
                    val webSocket = ElevenLabsService.createScribeWebSocket(
                        onTranscriptResult = { transcript, isFinal ->
                            scope.launch(Dispatchers.Main) {
                                streamingTranscript = transcript
                                if (isFinal && transcript.isNotBlank()) {
                                    messages = messages + ConversationMessage(text = transcript, isFromOther = true)
                                    streamingTranscript = ""
                                    autoSaveHistory()
                                    generateSuggestions()
                                }
                            }
                        },
                        onError = { error, isForbidden ->
                            scope.launch(Dispatchers.Main) {
                                errorMessage = if (isForbidden) "ElevenLabs Access Denied. Check Scribe permissions." else "STT Error: $error"
                                sttFallbackTriggered = true
                            }
                        }
                    )

                    if (webSocket == null) {
                        sttFallbackTriggered = true
                    } else {
                        val buf = ByteArray(bufferSize)
                        while (isActive && conversationState == ConversationState.LISTENING && !sttFallbackTriggered) {
                            val read = record.read(buf, 0, buf.size)
                            if (read > 0) {
                                // Protocol Fix: Scribe v2 requires JSON framing + Base64
                                val chunk = buf.copyOfRange(0, read)
                                ElevenLabsService.sendScribeAudio(webSocket, chunk)
                            }
                        }
                        webSocket.close(1000, "User stopped")
                    }
                } else {
                    sttFallbackTriggered = true
                }

                if (sttFallbackTriggered && isActive && conversationState == ConversationState.LISTENING) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Fallback: Whisper Offline", Toast.LENGTH_SHORT).show() }
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(bufferSize)
                    val targetBytes = sampleRate * 2 * 6 

                    while (out.size() < targetBytes && isActive && conversationState == ConversationState.LISTENING) {
                        val read = record.read(buf, 0, buf.size)
                        if (read > 0) out.write(buf, 0, read)
                    }

                    if (isActive && conversationState == ConversationState.LISTENING) {
                        withContext(Dispatchers.Main) { conversationState = ConversationState.TRANSCRIBING }
                        val audioBytes = out.toByteArray()
                        if (audioBytes.size >= sampleRate * 2) {
                            val transcript = RunAnywhere.transcribe(audioBytes).trim()
                            withContext(Dispatchers.Main) {
                                if (transcript.isNotBlank()) {
                                    messages = messages + ConversationMessage(text = transcript, isFromOther = true)
                                    autoSaveHistory()
                                    generateSuggestions()
                                } else {
                                    errorMessage = "No speech detected"
                                }
                            }
                        }
                    }
                }

                try { record.stop() } catch (e: Exception) {}
                record.release()
                withContext(Dispatchers.Main) { conversationState = ConversationState.IDLE }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMessage = "Error: ${e.message}" ; conversationState = ConversationState.IDLE }
            } finally {
                // Resume background sound classification
                AudioForegroundService.startMicCapture()
            }
        }
    }

    fun speakMyReply(text: String = myReply) {
        if (text.isBlank()) return
        val replyText = text.trim()
        
        // Add to message history only if it's not already the last message
        if (messages.lastOrNull()?.text != replyText || messages.lastOrNull()?.isFromOther == true) {
            messages = messages + ConversationMessage(text = replyText, isFromOther = false)
            autoSaveHistory()
        }
        
        if (text == myReply) myReply = ""
        suggestions = emptyList()

        scope.launch {
            try {
                var audioPlayed = false
                if (ElevenLabsService.isEnabled(context)) {
                    conversationState = ConversationState.GENERATING_SPEECH

                    // Fix: Pass context to ElevenLabsService.textToSpeech
                    val audioBytes = ElevenLabsService.textToSpeech(context, replyText)
                    if (audioBytes != null) {
                        conversationState = ConversationState.SPEAKING
                        // Standardized function name call
                        ElevenLabsService.playMp3Bytes(context, audioBytes)
                        audioPlayed = true
                    }
                } 
                
                if (!audioPlayed && ttsReady) {
                    conversationState = ConversationState.SPEAKING
                    if (ElevenLabsService.isEnabled(context)) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "TTS Fallback: Piper", Toast.LENGTH_SHORT).show() }
                    }
                    val output = withContext(Dispatchers.IO) { RunAnywhere.synthesize(replyText, TTSOptions()) }
                    playWavBytes(output.audioData)
                    audioPlayed = true
                }
                
                if (!audioPlayed) {
                    errorMessage = "Text-to-Speech unavailable"
                }
            } catch (e: Exception) {
                errorMessage = "TTS failed: ${e.message}"
            } finally {
                conversationState = ConversationState.IDLE
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.85f), drawerContainerColor = Color.White) {
                HistoryDrawerContent(
                    historyViewModel = historyViewModel,
                    type = HistoryType.CONVERSATION,
                    onItemSelected = { item ->
                        messages = item.content.map { ConversationMessage(it.text, it.fromOther, it.timestamp) }
                        currentSessionId = item.id
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = Color(0xFFF8F9FA),
            topBar = {
                Box(modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(softBlue, Color(0xFFA7C6FF)))).padding(top = 12.dp, bottom = 12.dp, start = 8.dp, end = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "History", tint = Color.White) }
                        IconButton(onClick = { onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Conversation", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (conversationState != ConversationState.IDLE) ConversationStateBadge(state = conversationState)
                        }
                        IconButton(onClick = { 
                            messages = emptyList()
                            currentSessionId = UUID.randomUUID().toString()
                            suggestions = emptyList()
                            errorMessage = null
                        }) { Icon(Icons.Default.Add, "New Chat", tint = Color.White) }
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).background(Color(0xFFF8F9FA))) {
                ModelStatusBar(sttReady, ttsReady, llmReady, elevenLabsEnabled)

                errorMessage?.let { error ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(red.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(error, fontSize = 12.sp, color = red, modifier = Modifier.weight(1f))
                        IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(20.dp)) { Icon(Icons.Default.Close, "Dismiss", tint = red, modifier = Modifier.size(14.dp)) }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (messages.isEmpty() && streamingTranscript.isEmpty()) {
                        ConversationEmptyState()
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(items = messages, key = { it.timestamp }) { msg -> ConversationBubble(message = msg) }
                            if (streamingTranscript.isNotEmpty()) { item { StreamingBubble(text = streamingTranscript) } }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (suggestions.isNotEmpty()) {
                            LazyRow(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(items = suggestions) { suggestion ->
                                    SuggestionChip(text = suggestion, onTap = { myReply = suggestion }, onSpeak = { speakMyReply(suggestion) }, purple = purple)
                                }
                            }
                        }

                        Button(
                            onClick = {
                                if (conversationState == ConversationState.LISTENING) { listenJob?.cancel() ; conversationState = ConversationState.IDLE }
                                else if (hasPermission) listenForOther()
                                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (conversationState == ConversationState.LISTENING) red else softBlue)
                        ) {
                            Icon(if (conversationState == ConversationState.LISTENING) Icons.Default.Stop else Icons.Default.Hearing, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (conversationState == ConversationState.LISTENING) "Stop Co-pilot" else "Listen (Co-pilot)")
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = myReply, 
                                onValueChange = { myReply = it }, 
                                modifier = Modifier.weight(1f), 
                                placeholder = { Text("Type your reply…", color = Color(0xFFB0B0B0)) }, 
                                shape = RoundedCornerShape(12.dp),
                                textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = purple, 
                                    unfocusedBorderColor = Color(0xFFEEF0F5),
                                    focusedTextColor = Color.Black,
                                    unfocusedTextColor = Color.Black
                                )
                            )
                            FloatingActionButton(onClick = { speakMyReply() }, modifier = Modifier.size(52.dp), containerColor = if (myReply.isBlank()) Color(0xFFEEF0F5) else purple, contentColor = if (myReply.isBlank()) Color(0xFFB0B0B0) else Color.White, elevation = FloatingActionButtonDefaults.elevation(0.dp)) {
                                if (conversationState == ConversationState.SPEAKING || conversationState == ConversationState.GENERATING_SPEECH) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                                else Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speak")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    val softBlue = Color(0xFF6FB1FC)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(softBlue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = softBlue, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.widthIn(max = 280.dp)) {
            Card(shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.7f)), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = text, fontSize = 15.sp, color = Color(0xFF1A2340).copy(alpha = 0.6f), lineHeight = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    PulsingDot(color = softBlue)
                }
            }
        }
    }
}

@Composable
private fun ConversationStateBadge(state: ConversationState) {
    val (label, color) = when (state) {
        ConversationState.IDLE -> return
        ConversationState.LISTENING -> "Listening" to Color(0xFFFF6B6B)
        ConversationState.TRANSCRIBING -> "Transcribing" to Color(0xFFFFD166)
        ConversationState.GENERATING_SUGGESTIONS -> "Thinking" to Color(0xFF9C6FFC)
        ConversationState.GENERATING_SPEECH -> "Loading Audio" to Color(0xFF6FB1FC)
        ConversationState.SPEAKING -> "Speaking" to Color(0xFF6BCB77)
    }
    Row(modifier = Modifier.clip(RoundedCornerShape(50.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModelStatusBar(sttReady: Boolean, ttsReady: Boolean, llmReady: Boolean, elevenLabsEnabled: Boolean) {
    val backgroundColor = if (elevenLabsEnabled) Color(0xFFE8F5E9) else Color(0xFFFFF8E1)
    val iconColor = if (elevenLabsEnabled) Color(0xFF4CAF50) else Color(0xFFFFD166)

    Row(modifier = Modifier.fillMaxWidth().background(backgroundColor).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (elevenLabsEnabled) Icons.Default.CloudDone else Icons.Default.Warning, null, tint = iconColor, modifier = Modifier.size(18.dp))
        Column {
            Text(if (elevenLabsEnabled) "AI Co-pilot: ElevenLabs Active" else "Offline Models Status:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A2340))
            Text(buildString {
                if (elevenLabsEnabled) {
                    append("Cloud Scribe STT, smart replies, and premium voice enabled")
                } else {
                    if (!sttReady) append("STT Down ")
                    if (!ttsReady) append("TTS Down ")
                    if (!llmReady) append("LLM Down ")
                    if (sttReady && ttsReady && llmReady) append("All Offline Models Ready")
                }
            }, fontSize = 11.sp, color = Color(0xFF6B7A9A))
        }
    }
}

@Composable
private fun ConversationEmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF6FB1FC).copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Forum, null, tint = Color(0xFF6FB1FC), modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("Start a conversation", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A2340))
            Spacer(Modifier.height(8.dp))
            Text("Tap 'Listen (Co-pilot)' for ElevenLabs Realtime transcription and smart replies.", fontSize = 13.sp, color = Color(0xFF6B7A9A), textAlign = TextAlign.Center, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun SuggestionChip(text: String, onTap: () -> Unit, onSpeak: () -> Unit, purple: Color) {
    Row(modifier = Modifier.clip(RoundedCornerShape(50.dp)).background(purple.copy(alpha = 0.08f)).border(1.dp, purple.copy(alpha = 0.25f), RoundedCornerShape(50.dp))) {
        TextButton(onClick = onTap, contentPadding = PaddingValues(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) { Text(text, fontSize = 12.sp, color = Color(0xFF1A2340), maxLines = 1) }
        IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp).padding(end = 6.dp)) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Speak", tint = purple, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val infiniteTransition = rememberInfiniteTransition("dot")
    val scale by infiniteTransition.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse), "dotScale")
    Box(modifier = Modifier.size(10.dp).scale(scale).clip(CircleShape).background(color))
}

@Composable
private fun ConversationBubble(message: ConversationMessage) {
    val isOther = message.isFromOther
    val softBlue = Color(0xFF6FB1FC)
    val purple   = Color(0xFF9C6FFC)
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isOther) Arrangement.Start else Arrangement.End) {
        if (isOther) {
            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(softBlue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, null, tint = softBlue, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(8.dp))
        }
        val bubbleAlignment: Alignment.Horizontal = if (isOther) Alignment.Start else Alignment.End
        Column(horizontalAlignment = bubbleAlignment, modifier = Modifier.widthIn(max = 280.dp)) {
            Card(shape = RoundedCornerShape(topStart = if (isOther) 4.dp else 18.dp, topEnd = if (isOther) 18.dp else 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp), colors = CardDefaults.cardColors(containerColor = if (isOther) Color.White else purple), elevation = CardDefaults.cardElevation(2.dp)) {
                Text(text = message.text, fontSize = 15.sp, color = if (isOther) Color(0xFF1A2340) else Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), lineHeight = 22.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(text = timeFormat.format(Date(message.timestamp)), fontSize = 10.sp, color = Color(0xFFB0B0B0), modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (!isOther) {
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(purple.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = purple, modifier = Modifier.size(20.dp)) }
        }
    }
}
