package com.duyue.reader.data

import android.content.Context
import java.io.File

/** 偏好设置：字号、主题、AI Key、TTS 语速等 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("duyue_prefs", Context.MODE_PRIVATE)

    var fontSize: Float
        get() = sp.getFloat("font_size", 19f)
        set(v) = sp.edit().putFloat("font_size", v).apply()

    var lineHeight: Float
        get() = sp.getFloat("line_height", 1.8f)
        set(v) = sp.edit().putFloat("line_height", v).apply()

    var theme: Int
        get() = sp.getInt("theme", 2)   // 默认夜间模式（用户偏好）
        set(v) = sp.edit().putInt("theme", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "").orEmpty()
        set(v) = sp.edit().putString("api_key", v).apply()

    var aiModel: String
        get() = sp.getString("ai_model", "deepseek-chat").orEmpty()
        set(v) = sp.edit().putString("ai_model", v).apply()

    var ttsRate: Float
        get() = sp.getFloat("tts_rate", 1.0f)
        set(v) = sp.edit().putFloat("tts_rate", v).apply()

    var ttsPitch: Float
        get() = sp.getFloat("tts_pitch", 1.0f)
        set(v) = sp.edit().putFloat("tts_pitch", v).apply()

    var ttsEngine: String
        get() = sp.getString("tts_engine", "").orEmpty()
        set(v) = sp.edit().putString("tts_engine", v).apply()

    var lyricOverlayOn: Boolean
        get() = sp.getBoolean("lyric_overlay", false)
        set(v) = sp.edit().putBoolean("lyric_overlay", v).apply()

    /** 上次朗读到的句子位置（章节号_句子号），用于恢复 */
    var lastSentencePos: Int
        get() = sp.getInt("last_sentence_pos", 0)
        set(v) = sp.edit().putInt("last_sentence_pos", v).apply()

    // ---- 悬浮窗背景：true=不透明（默认），false=透明 ----
    var lyricBgOpaque: Boolean
        get() = sp.getBoolean("lyric_bg_opaque", true)
        set(v) = sp.edit().putBoolean("lyric_bg_opaque", v).apply()

    // ---- 悬浮窗背景透明度 0~100（0=全透明） ----
    var lyricBgAlpha: Int
        get() = sp.getInt("lyric_bg_alpha", 100).coerceIn(0, 100)
        set(v) = sp.edit().putInt("lyric_bg_alpha", v.coerceIn(0, 100)).apply()

    // ---- 用户挑好的「问 AI / 查词」目标 App ----
    // 存成 "key|label|packageName|activityName" 的列表
    var textTargets: List<String>
        get() = sp.getStringSet("text_targets", emptySet())?.toList() ?: emptyList()
        set(v) = sp.edit().putStringSet("text_targets", v.toSet()).apply()

    fun addTextTarget(encoded: String) {
        val cur = textTargets.toMutableList()
        if (cur.none { it.substringBefore("|") == encoded.substringBefore("|") }) {
            cur.add(encoded)
            textTargets = cur.take(4)   // 最多 4 个
        }
    }

    fun removeTextTarget(key: String) {
        textTargets = textTargets.filterNot { it.substringBefore("|") == key }
    }

    // ---- 阅读进度：每本书每章读到第几个片段 ----
    // 键名格式："pos_<bookId>_<chapterIndex>"，值是该章内的片段下标
    fun saveChapterPos(bookId: String, chapterIndex: Int, clipIndex: Int) {
        sp.edit().putInt("pos_${bookId}_$chapterIndex", clipIndex).apply()
    }

    fun getChapterPos(bookId: String, chapterIndex: Int): Int =
        sp.getInt("pos_${bookId}_$chapterIndex", 0)

    /** 清掉某本书的全部进度（删除书时调用） */
    fun clearBookPos(bookId: String) {
        val editor = sp.edit()
        sp.all.keys.filter { it.startsWith("pos_${bookId}_") }.forEach { editor.remove(it) }
        editor.apply()
    }
}

/** AI 摘要缓存：同一章不重复请求，省钱 */
class SummaryCache(context: Context) {
    private val dir = File(context.filesDir, "summaries").apply { mkdirs() }

    private fun fileFor(bookId: String, chapterIndex: Int) =
        File(dir, "${bookId}_$chapterIndex.md")

    fun get(bookId: String, chapterIndex: Int): String? {
        val f = fileFor(bookId, chapterIndex)
        return if (f.exists()) f.readText() else null
    }

    fun put(bookId: String, chapterIndex: Int, text: String) {
        runCatching { fileFor(bookId, chapterIndex).writeText(text) }
    }

    fun clear(bookId: String, chapterIndex: Int) {
        runCatching { fileFor(bookId, chapterIndex).delete() }
    }
}
