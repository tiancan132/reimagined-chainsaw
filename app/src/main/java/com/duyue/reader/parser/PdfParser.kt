package com.duyue.reader.parser

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.duyue.reader.model.Chapter

/**
 * PDF 解析。
 * 注意：安卓原生的 PdfRenderer 只能「渲染页面为图片」，无法直接抽取文字。
 * 因此 PDF 的阅读模式是「按页渲染成图」，而不是重排版文字。
 * 这里把每页封装成一个 Chapter，供阅读器与 TTS 统一处理。
 * （TTS 对 PDF 页：目前先支持朗读用户在页面上选中的文字）
 */
object PdfParser {

    fun pageCount(context: Context, uri: Uri): Int {
        val pfd = open(context, uri) ?: return 0
        return pfd.use { PdfRenderer(it).use { r -> r.pageCount } }
    }

    fun buildChapters(pageCount: Int): List<Chapter> =
        (0 until pageCount).map { Chapter(it, "第 ${it + 1} 页", "") }

    private fun open(context: Context, uri: Uri): ParcelFileDescriptor? = try {
        context.contentResolver.openFileDescriptor(uri, "r")
    } catch (e: Exception) {
        null
    }
}
