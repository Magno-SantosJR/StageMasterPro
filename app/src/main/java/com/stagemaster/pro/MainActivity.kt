package com.stagemaster.pro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.stagemaster.pro.audio.MultitrackEngine
import com.stagemaster.pro.model.*
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var onPedalKeyEvent: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mantém a tela do Tab S6 sempre acesa durante o show
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StageMasterScreen(
                    registerPedalListener = { listener -> onPedalKeyEvent = listener }
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (onPedalKeyEvent?.invoke(keyCode) == true) return true
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun StageMasterScreen(registerPedalListener: ((Int) -> Boolean) -> Unit) {
    val context = LocalContext.current

    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var stopAfterCurrent by remember { mutableStateOf(false) }
    var playSingleOnly by remember { mutableStateOf(false) }
    var showMixerDrawer by remember { mutableStateOf(true) }

    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(1L) }

    val currentSong = songs.getOrNull(currentIndex)

    lateinit var engine: MultitrackEngine
    engine = remember {
        MultitrackEngine(
            context = context,
            onTrackFinished = {
                isPlaying = false
                if (stopAfterCurrent || playSingleOnly) {
                    stopAfterCurrent = false
                    playSingleOnly = false
                } else if (currentIndex + 1 < songs.size) {
                    currentIndex += 1
                    val nextSong = songs[currentIndex]
                    engine.loadSong(nextSong, autoPlay = true)
                    isPlaying = true
                }
            },
            onProgressUpdate = { pos, dur ->
                currentPosMs = pos
                durationMs = dur
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { engine.releaseAll() }
    }

    // Mapeamento inicial do Pedal M-Vave Cube Turner
    LaunchedEffect(songs, currentIndex, stopAfterCurrent) {
        registerPedalListener { keyCode ->
            when (keyCode) {
                KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (currentIndex + 1 < songs.size) {
                        currentIndex++
                        songs.getOrNull(currentIndex)?.let { engine.loadSong(it, autoPlay = isPlaying) }
                    }
                    true
                }
                KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (currentIndex > 0) {
                        currentIndex--
                        songs.getOrNull(currentIndex)?.let { engine.loadSong(it, autoPlay = isPlaying) }
                    }
                    true
                }
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER -> {
                    // Alterna "Parar ao fim desta música" no toque longo do pedal
                    stopAfterCurrent = !stopAfterCurrent
                    true
                }
                else -> false
            }
        }
    }

    // Seletor de pasta raiz do Show (Memória Interna ou microSD do Tab S6)
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            val loadedSongs = mutableListOf<SongItem>()

            // Cada subpasta vira uma música com suas faixas de áudio e cifra/partitura
            rootDoc?.listFiles()?.sortedBy { it.name }?.forEach { folder ->
                if (folder.isDirectory) {
                    val tracks = mutableListOf<AudioTrackChannel>()
                    var chordText = ""
                    var pdfUri: Uri? = null

                    folder.listFiles().sortedBy { it.name }.forEach { file ->
                        val name = file.name ?: return@forEach
                        val lower = name.lowercase()
                        when {
                            lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".m4a") -> {
                                tracks.add(
                                    AudioTrackChannel(
                                        id = UUID.randomUUID().toString(),
                                        name = name.substringBeforeLast("."),
                                        uri = file.uri
                                    )
                                )
                            }
                            lower.endsWith(".txt") -> {
                                chordText = context.contentResolver.openInputStream(file.uri)
                                    ?.bufferedReader()?.use { it.readText() } ?: ""
                            }
                            lower.endsWith(".pdf") -> {
                                pdfUri = file.uri
                            }
                        }
                    }

                    if (tracks.isNotEmpty() || chordText.isNotEmpty() || pdfUri != null) {
                        loadedSongs.add(
                            SongItem(
                                id = UUID.randomUUID().toString(),
                                title = folder.name ?: "Música",
                                sheetType = if (pdfUri != null) SheetType.SCORE_PDF else SheetType.CHORD_TEXT,
                                chordContent = chordText,
                                pdfUri = pdfUri,
                                tracks = tracks
                            )
                        )
                    }
                }
            }

            if (loadedSongs.isNotEmpty()) {
                songs = loadedSongs
                currentIndex = 0
                engine.loadSong(loadedSongs[0], autoPlay = false)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E14))
    ) {
        // COLUNA ESQUERDA: Bloco do Show + Mixer Multitrack
        Column(
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight()
                .background(Color(0xFF131822))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("BLOCO DO SHOW", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Button(
                    onClick = { folderPicker.launch(null) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pasta VS", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Lista das músicas do bloco
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(songs) { index, song ->
                    val selected = index == currentIndex
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable {
                                currentIndex = index
                                playSingleOnly = false
                                engine.loadSong(song, autoPlay = false)
                                isPlaying = false
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) Color(0xFF1E3A8A) else Color(0xFF1E293B)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${index + 1}. ${song.title}",
                                    color = Color.White,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "${song.tracks.size} canais VS • ${song.targetBpm} BPM",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            // Botão para tocar SÓ esta música avulsa
                            IconButton(
                                onClick = {
                                    currentIndex = index
                                    playSingleOnly = true
                                    stopAfterCurrent = true
                                    engine.loadSong(song, autoPlay = true)
                                    isPlaying = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.PlayCircleOutline, contentDescription = "Tocar só esta", tint = Color(0xFF38BDF8))
                            }
                        }
                    }
                }
            }

            // MIXER DE INSTRUMENTOS (Bateria, Guitarra, Baixo, Click, Guia...)
            if (showMixerDrawer && currentSong != null && currentSong.tracks.isNotEmpty()) {
                HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 8.dp))
                Text("MIXER MULTITRACK (VS)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))

                Column(
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    currentSong.tracks.forEachIndexed { trackIdx, track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                track.name,
                                color = if (track.isMuted) Color.Gray else Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            // Botão MUTE
                            FilterChip(
                                selected = track.isMuted,
                                onClick = {
                                    val updatedTracks = currentSong.tracks.toMutableList().apply {
                                        this[trackIdx] = track.copy(isMuted = !track.isMuted)
                                    }
                                    val updatedSong = currentSong.copy(tracks = updatedTracks)
                                    songs = songs.toMutableList().apply { this[currentIndex] = updatedSong }
                                    engine.applyMix(updatedTracks)
                                },
                                label = { Text("M", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFDC2626),
                                    selectedLabelColor = Color.White
                                )
                            )
                            Spacer(Modifier.width(4.dp))
                            // Botão SOLO
                            FilterChip(
                                selected = track.isSolo,
                                onClick = {
                                    val updatedTracks = currentSong.tracks.toMutableList().apply {
                                        this[trackIdx] = track.copy(isSolo = !track.isSolo)
                                    }
                                    val updatedSong = currentSong.copy(tracks = updatedTracks)
                                    songs = songs.toMutableList().apply { this[currentIndex] = updatedSong }
                                    engine.applyMix(updatedTracks)
                                },
                                label = { Text("S", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFEAB308),
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                }
            }
        }

        // COLUNA DIREITA: Controles de Palco + Visualizador de Cifra
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            // Barra Superior de Transporte & BPM
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF131822), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Play / Pause Bloco
                    Button(
                        onClick = {
                            if (currentSong != null) {
                                if (isPlaying) {
                                    engine.pause()
                                    isPlaying = false
                                } else {
                                    engine.play()
                                    isPlaying = true
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) Color(0xFFF59E0B) else Color(0xFF10B981)
                        )
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPlaying) "PAUSAR" else "TOCAR BLOCO", fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.width(8.dp))

                    // Stop Imediato
                    OutlinedButton(onClick = {
                        engine.stop()
                        isPlaying = false
                    }) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = Color(0xFFEF4444))
                    }

                    Spacer(Modifier.width(12.dp))

                    // BOTÃO ARMADO: PARAR AO FIM DESTA MÚSICA
                    Button(
                        onClick = { stopAfterCurrent = !stopAfterCurrent },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (stopAfterCurrent) Color(0xFFDC2626) else Color(0xFF1E293B)
                        )
                    ) {
                        Text(
                            if (stopAfterCurrent) "⏹ PARADA ARMADA (FIM DA MÚSICA)" else "Parar no Fim",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Controle de BPM em tempo real (sem mudar o tom)
                if (currentSong != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            val newBpm = (currentSong.targetBpm - 1).coerceAtLeast(40)
                            val updated = currentSong.copy(targetBpm = newBpm)
                            songs = songs.toMutableList().apply { this[currentIndex] = updated }
                            engine.setBpm(currentSong.originalBpm, newBpm)
                        }) {
                            Text("-1", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "${currentSong.targetBpm} BPM",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        IconButton(onClick = {
                            val newBpm = (currentSong.targetBpm + 1).coerceAtMost(260)
                            val updated = currentSong.copy(targetBpm = newBpm)
                            songs = songs.toMutableList().apply { this[currentIndex] = updated }
                            engine.setBpm(currentSong.originalBpm, newBpm)
                        }) {
                            Text("+1", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Barra de progresso do VS
            Slider(
                value = (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                onValueChange = { ratio ->
                    engine.seekTo((ratio * durationMs).toLong())
                },
                modifier = Modifier.fillMaxWidth()
            )

            // Área da Cifra
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0F131A), RoundedCornerShape(10.dp))
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (currentSong == null) {
                    Text(
                        "Toque em 'Pasta VS' à esquerda e selecione a pasta principal do seu show no Tab S6 ou cartão microSD.\n\nOrganize cada música em uma subpasta contendo os arquivos de áudio (Bateria.wav, Guitarra.wav, Click.wav...) e o arquivo cifra.txt.",
                        color = Color(0xFF94A3B8),
                        fontSize = 16.sp
                    )
                } else {
                    Text(
                        text = currentSong.chordContent.ifEmpty { "Música carregada (${currentSong.tracks.size} canais de VS). Adicione um arquivo cifra.txt na pasta desta música." },
                        color = Color(0xFFF8FAFC),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        lineHeight = 26.sp
                    )
                }
            }
        }
    }
}