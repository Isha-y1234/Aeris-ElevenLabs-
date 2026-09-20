package com.runanywhere.kotlin_starter_example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.runanywhere.kotlin_starter_example.services.AudioForegroundService
import com.runanywhere.kotlin_starter_example.services.ElevenLabsService
import com.runanywhere.kotlin_starter_example.services.ModelService
import com.runanywhere.kotlin_starter_example.services.playWavBytes
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.TTS.TTSOptions
import com.runanywhere.sdk.public.extensions.synthesize
import kotlinx.coroutines.*

enum class VoiceProxyState {
    IDLE,
    GENERATING,
    SPEAKING
}

@Composable
fun VoiceProxyScreen(
    modelService: ModelService,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var proxyState by remember { mutableStateOf(VoiceProxyState.IDLE) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val recentPhrases = remember {
        mutableStateListOf(
            "I am deaf — please write or type",
            "Can you repeat that please?",
            "Speak slower please",
            "One moment, I am reading"
        )
    }

    val softBlue = Color(0xFF6FB1FC)
    val softBlueEnd = Color(0xFFA7C6FF)

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        errorMessage = null
        
        // release background mic to avoid hardware overlap during playback
        AudioForegroundService.stopMicCapture()
        
        try {
            var audioPlayed = false
            
            // 1. Primary: ElevenLabs TTS
            if (ElevenLabsService.isEnabled(context)) {
                proxyState = VoiceProxyState.GENERATING
                // Fix: Pass context to ElevenLabsService.textToSpeech
                val audioBytes = ElevenLabsService.textToSpeech(context, text)
                if (audioBytes != null) {
                    proxyState = VoiceProxyState.SPEAKING
                    ElevenLabsService.playMp3Bytes(context, audioBytes)
                    audioPlayed = true
                }
            }
            
            // 2. Fallback: Piper Offline
            if (!audioPlayed && modelService.isTTSLoaded) {
                proxyState = VoiceProxyState.SPEAKING
                if (ElevenLabsService.isEnabled(context)) {
                    withContext(Dispatchers.Main) { 
                        Toast.makeText(context, "Cloud TTS unavailable, using offline voice", Toast.LENGTH_SHORT).show() 
                    }
                }
                val output = withContext(Dispatchers.IO) {
                    RunAnywhere.synthesize(text, TTSOptions())
                }
                playWavBytes(output.audioData)
                audioPlayed = true
            }
            
            if (!audioPlayed) {
                errorMessage = "Voice models unavailable. Check internet or model settings."
            } else {
                if (!recentPhrases.contains(text)) {
                    recentPhrases.add(0, text)
                    if (recentPhrases.size > 6) recentPhrases.removeLastOrNull()
                }
            }
        } catch (e: Exception) {
            errorMessage = "TTS failed: ${e.message}"
        } finally {
            proxyState = VoiceProxyState.IDLE
            // Resume background monitoring
            AudioForegroundService.startMicCapture()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(softBlue, softBlueEnd)))
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Voice Proxy", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Type — ElevenLabs premium voice speaks for you", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Status Card
        if (ElevenLabsService.isEnabled(context)) {
            StatusCard(label = "ElevenLabs Cloud Active", color = Color(0xFF6BCB77), icon = Icons.Default.CloudDone)
        } else {
            StatusCard(label = "Offline Model Active", color = Color(0xFFFFD166), icon = Icons.Default.CloudOff)
        }

        Spacer(Modifier.height(16.dp))

        // Text input card
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("What do you want to say?", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7A9A))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type your message here…", color = Color(0xFFB0B0B0)) },
                    minLines = 4,
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.Black,
                        unfocusedTextColor = Color.Black,
                        focusedBorderColor = softBlue,
                        unfocusedBorderColor = Color(0xFFEEF0F5)
                    )
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { scope.launch { speak(inputText) } },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = softBlue),
                    enabled = proxyState == VoiceProxyState.IDLE && inputText.isNotBlank()
                ) {
                    if (proxyState != VoiceProxyState.IDLE) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (proxyState == VoiceProxyState.GENERATING) "Generating…" else "Speaking…", fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Speak Aloud", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Quick phrases
        Text("Quick Phrases", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF6B7A9A), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(8.dp))

        recentPhrases.forEach { phrase ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).clickable {
                    if (proxyState == VoiceProxyState.IDLE) { scope.launch { speak(phrase) } }
                },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = softBlue, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(phrase, fontSize = 14.sp, color = Color(0xFF1A2340), modifier = Modifier.weight(1f))
                    Icon(Icons.Default.PlayArrow, null, tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
                }
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFF6B6B).copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(it, modifier = Modifier.padding(16.dp), fontSize = 13.sp, color = Color(0xFFFF6B6B))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StatusCard(label: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 12.sp, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}
