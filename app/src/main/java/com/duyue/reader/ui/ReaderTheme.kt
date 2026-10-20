package com.duyue.reader.ui

import androidx.compose.ui.graphics.Color

/** 阅读主题：纸白 / 护眼绿 / 夜间 */
enum class ReaderTheme(
    val label: String,
    val bg: Color,
    val text: Color,
    val sub: Color,
    val accent: Color
) {
    PAPER("纸白", Color(0xFFF7F3EA), Color(0xFF2B2B2B), Color(0xFF8A8578), Color(0xFF9A7B47)),
    GREEN("护眼", Color(0xFFD6E8D2), Color(0xFF2C3328), Color(0xFF6B7A66), Color(0xFF4E7A46)),
    NIGHT("夜间", Color(0xFF17181A), Color(0xFFB8B8B8), Color(0xFF6B6B6B), Color(0xFF7EA6E0)),
    SEPIA("牛皮", Color(0xFFEDE0C8), Color(0xFF4A3B28), Color(0xFF8C7A5E), Color(0xFF8B6B3E));

    companion object {
        fun of(index: Int): ReaderTheme = entries.getOrElse(index) { PAPER }
    }
}

val HighlightColors = listOf<String>(
    "本机 TTS", "系统 TTS 引擎", "onRangeStart", "前台服务", "悬浮窗"
)
