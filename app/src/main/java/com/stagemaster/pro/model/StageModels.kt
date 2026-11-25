package com.stagemaster.pro.model

import android.net.Uri

data class AudioTrackChannel(
    val id: String,
    val name: String,        // Ex: "Bateria", "Guitarra", "Click", "Guia"
    val uri: Uri,
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val isSolo: Boolean = false
)

enum class SheetType {
    CHORD_TEXT,
    SCORE_PDF
}

data class SongItem(
    val id: String,
    val title: String,
    val originalBpm: Int = 120,
    val targetBpm: Int = 120,
    val keySignature: String = "C",
    val sheetType: SheetType = SheetType.CHORD_TEXT,
    val chordContent: String = "",
    val pdfUri: Uri? = null,
    val tracks: List<AudioTrackChannel> = emptyList()
)

data class ShowBlock(
    val id: String,
    val name: String,
    val songs: List<SongItem>
)