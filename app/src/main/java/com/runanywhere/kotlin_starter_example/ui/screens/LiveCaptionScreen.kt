package com.runanywhere.kotlin_starter_example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runanywhere.kotlin_starter_example.data.CaptionLine
import com.runanywhere.kotlin_starter_example.data.HistoryContentLine
import com.runanywhere.kotlin_starter_example.data.HistoryType
import com.runanywhere.kotlin_starter_example.services.AudioForegroundService
import com.runanywhere.kotlin_starter_example.services.ElevenLabsService
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.viewmodel.ExportState
import com.runanywhere.kotlin_starter_example.viewmodel.HistoryViewModel
import com.runanywhere.kotlin_starter_example.viewmodel.MainViewModel
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.transcribe
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LiveCaptionScreen(
    modelService: ModelService,
    historyViewModel: HistoryViewModel,
    onBack: () -> Unit,
    mainViewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var isLive by remember { mutableStateOf(false) }
    var captions by remember { mutableStateOf(listOf<CaptionLine>()) }
    var hasPermission by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var streamingTranscript by remember { mutableStateOf("") }
    var isUsingElevenLabs by remember { mutableStateOf(false) }

    val exportState by mainViewModel.exportState.collectAsState(ExportState.Idle)

    LaunchedEffect(exportState) {
        when (val state = exportState) {
            is ExportState.Success -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, state.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Transcript"))
            }
            is ExportState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    LaunchedEffect(Unit) {
        hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        historyViewModel.loadHistory(HistoryType.CAPTION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(captions.size, streamingTranscript) {
        if (captions.isNotEmpty() || streamingTranscript.isNotEmpty()) {
            val targetIndex = if (streamingTranscript.isNotEmpty()) captions.size else captions.size - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    fun startCaption() {
        isLive = true
        streamingTranscript = ""
        isUsingElevenLabs = ElevenLabsService.isEnabled(context)
        
        // Critical: Pause background sound classifier to release microphone
        AudioForegroundService.stopMicCapture()

        captureJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            try {
                val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 4)
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "Microphone access failed", Toast.LENGTH_SHORT).show()
                        isLive = false 
                    }
                    AudioForegroundService.startMicCapture()
                    return@launch
                }
                audioRecord.startRecording()

                var sttFallbackTriggered = false

                if (isUsingElevenLabs) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Cloud Scribe Active", Toast.LENGTH_SHORT).show() }
                    val webSocket = ElevenLabsService.createScribeWebSocket(
                        onTranscriptResult = { transcript, isFinal ->
                            scope.launch(Dispatchers.Main) {
                                streamingTranscript = transcript
                                if (isFinal && transcript.isNotBlank()) {
                                    captions = captions + CaptionLine(text = transcript)
                                    streamingTranscript = ""
                                }
                            }
                        },
                        onError = { error, _ ->
                            scope.launch(Dispatchers.Main) {
                                Log.e("LiveCaption", "Scribe Error: $error")
                                sttFallbackTriggered = true
                                isUsingElevenLabs = false
                            }
                        }
                    )

                    if (webSocket != null) {
                        val buf = ByteArray(bufferSize)
                        while (isActive && isLive && !sttFallbackTriggered) {
                            val read = audioRecord.read(buf, 0, buf.size)
                            if (read > 0) {
                                // FIXED: Using new JSON-based send method
                                val chunk = buf.copyOfRange(0, read)
                                val sent = ElevenLabsService.sendScribeAudio(webSocket, chunk)
                                if (!sent) {
                                    sttFallbackTriggered = true
                                }
                            }
                        }
                        webSocket.close(1000, "Session ended")
                    } else {
                        sttFallbackTriggered = true
                    }
                } else {
                    sttFallbackTriggered = true
                }

                if (sttFallbackTriggered && isActive && isLive) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "Fallback: Whisper Offline", Toast.LENGTH_SHORT).show() }
                    while (isActive && isLive) {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(bufferSize)
                        val targetBytes = sampleRate * 2 * 3
                        while (out.size() < targetBytes && isActive && isLive) {
                            val read = audioRecord.read(buffer, 0, buffer.size)
                            if (read > 0) out.write(buffer, 0, read)
                        }
                        if (!isActive || !isLive) break
                        val transcript = RunAnywhere.transcribe(out.toByteArray()).trim()
                        if (transcript.isNotBlank()) {
                            withContext(Dispatchers.Main) { captions = captions + CaptionLine(text = transcript) }
                        }
                    }
                }

                try { audioRecord.stop() } catch (e: Exception) {}
                audioRecord.release()
            } catch (e: Exception) {
                Log.e("LiveCaption", "Capture Error: ${e.message}")
                withContext(Dispatchers.Main) { isLive = false }
            } finally {
                // Resume background monitoring when done
                AudioForegroundService.startMicCapture()
            }
        }
    }

    fun stopCaption() {
        isLive = false
        captureJob?.cancel()
        captureJob = null
        streamingTranscript = ""
        AudioForegroundService.startMicCapture()
    }

    fun saveToHistoryAndExit() {
        if (captions.isNotEmpty()) {
            val historyContent = captions.map { HistoryContentLine(it.text, fromOther = true, it.timestamp) }
            historyViewModel.saveSession(HistoryType.CAPTION, historyContent)
        }
        onBack()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.85f), drawerContainerColor = Color.White) {
                HistoryDrawerContent(
                    historyViewModel = historyViewModel,
                    type = HistoryType.CAPTION,
                    onItemSelected = { item ->
                        captions = item.content.map { CaptionLine(text = it.text, timestamp = it.timestamp) }
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Box(modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF6FB1FC), Color(0xFFA7C6FF)))).padding(top = 12.dp, bottom = 12.dp, start = 8.dp, end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Icon(Icons.Default.Menu, "History", tint = Color.White) }
                        IconButton(onClick = { saveToHistoryAndExit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Live Captions", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            if (isLive) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isUsingElevenLabs) Color(0xFF6BCB77) else Color(0xFFFFD166)))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (isUsingElevenLabs) "ElevenLabs Cloud" else "Local Offline", fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f))
                                }
                            }
                        }
                        IconButton(onClick = { mainViewModel.exportCaptions(context, captions) }, enabled = captions.isNotEmpty()) { Icon(Icons.Default.Share, "Export", tint = Color.White) }
                        if (captions.isNotEmpty()) {
                            IconButton(onClick = {
                                val historyContent = captions.map { HistoryContentLine(it.text, fromOther = true, it.timestamp) }
                                historyViewModel.saveSession(HistoryType.CAPTION, historyContent)
                                captions = emptyList()
                            }) { Icon(Icons.Default.Add, "New Session", tint = Color.White) }
                        }
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).background(Color(0xFFF8F9FA))) {
                Box(modifier = Modifier.weight(1f)) {
                    if (captions.isEmpty() && streamingTranscript.isEmpty()) {
                        EmptyCaptionsState()
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items = captions, key = { it.timestamp }) { msg -> CaptionLineCard(message = msg) }
                            if (streamingTranscript.isNotEmpty()) {
                                item {
                                    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.7f)), elevation = CardDefaults.cardElevation(1.dp), modifier = Modifier.fillMaxWidth()) {
                                        Text(streamingTranscript, fontSize = 16.sp, color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.padding(14.dp), lineHeight = 24.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(8.dp)) {
                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { if (isLive) stopCaption() else if (hasPermission) startCaption() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isLive) Color(0xFFFF6B6B) else Color(0xFF6FB1FC))
                        ) {
                            Icon(if (isLive) Icons.Default.Stop else Icons.Default.Mic, null)
                            Spacer(Modifier.width(12.dp))
                            Text(if (isLive) "Stop Listening" else "Start Live Caption", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyCaptionsState() {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF6FB1FC).copy(alpha = 0.1f)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, null, tint = Color(0xFF6FB1FC), modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(24.dp))
        Text("No captions yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1A2340))
        Spacer(Modifier.height(8.dp))
        Text("Tap the button below to start transcribing speech in real-time.", textAlign = TextAlign.Center, fontSize = 14.sp, color = Color(0xFF6B7A9A))
    }
}

@Composable
fun CaptionLineCard(message: CaptionLine) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = message.text, fontSize = 16.sp, color = Color(0xFF1A2340), lineHeight = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(text = timeFormat.format(Date(message.timestamp)), fontSize = 10.sp, color = Color(0xFFB0B0B0), modifier = Modifier.align(Alignment.End))
        }
    }
}
