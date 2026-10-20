package com.duyue.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 一句话字幕条 —— 听书时浮在屏幕底部，卡拉OK式高亮 */
@Composable
fun SubtitleBar(
    sentence: String,
    charStart: Int,
    charEnd: Int,
    theme: ReaderTheme,
    isSpeaking: Boolean,
    modifier: Modifier = Modifier
) {
    if (sentence.isBlank()) return
    val fg = if (theme == ReaderTheme.NIGHT) Color(0xFFE8E8E8) else Color(0xFFF5F5F5)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .background(
                color = if (theme == ReaderTheme.NIGHT) Color(0xE6222429) else Color(0xE62A2A2A),
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        val annot = if (isSpeaking && charEnd > charStart) {
            androidx.compose.ui.text.buildAnnotatedString {
                append(sentence)
                val s = charStart.coerceIn(0, sentence.length)
                val e = charEnd.coerceIn(s, sentence.length)
                if (e > s) {
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            color = theme.accent,
                            fontWeight = FontWeight.Bold
                        ),
                        start = s,
                        end = e
                    )
                }
            }
        } else {
            androidx.compose.ui.text.buildAnnotatedString {
                append(sentence)
            }
        }

        Text(
            text = annot,
            color = fg,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
