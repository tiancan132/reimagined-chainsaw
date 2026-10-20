package com.duyue.reader.parser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.duyue.reader.model.BookFormat
import java.nio.charset.Charset

/** 读取 SAF 提供的文件内容，自动处理中文编码 */
object FileReaderUtil {

    fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "未命名"
    }

    fun detectFormat(displayName: String): BookFormat? = when {
        displayName.endsWith(".txt", true) -> BookFormat.TXT
        displayName.endsWith(".epub", true) -> BookFormat.EPUB
        displayName.endsWith(".pdf", true) -> BookFormat.PDF
        else -> null
    }

    /**
     * 读取 TXT 全文。
     * 中文 TXT 常见编码：UTF-8（带或不带 BOM）、GBK/GB18030（老书站大量存在）。
     * 策略：先看 BOM，再用 UTF-8 严格解码，失败则退回 GB18030。
     */
    fun readText(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return decodeText(bytes)
    }

    fun decodeText(bytes: ByteArray): String {
        // BOM 检测
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 严格 UTF-8 解码尝试
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            // 中文老书站绝大多数是 GBK/GB18030
            String(bytes, Charset.forName("GB18030"))
        }
    }
}
