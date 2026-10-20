package com.duyue.reader.model

/**
 * 朗读片段。
 *
 * 关键：每个片段显式记录它属于第几段（paragraphIndex）。
 * 这样「点击某段从哪开始读」和「当前读到哪一段」都是**精确映射**，
 * 不需要事后靠字数累加去猜（之前就是这样猜错的，导致一点击就跳到章节末尾）。
 */
data class Clip(
    val text: String,
    /** 所属自然段的序号（从 0 开始） */
    val paragraphIndex: Int
)

/**
 * 一本书里的一个章节。
 *
 * 同一份正文有两套切分结果，用途不同：
 *   · paragraphs —— 按自然段（换行）切，用于**阅读页排版**（首行缩进、整段显示）
 *   · clips      —— 按标点切小片段（8~35 字），用于**朗读和悬浮窗字幕**，
 *                   且每个片段都带着自己的段落归属
 *
 * ⚠️ 性能要点：切分结果用 by lazy 缓存。
 * 之前写在构造参数默认值里，每次 new / copy 都会把整章重新切一遍，
 * 一本 523 章的书在解析 JSON 时要切几千次，直接卡死甚至闪退。
 */
class Chapter(
    val index: Int,
    val title: String,
    val content: String
) {
    /** 自然段列表（首次访问时才切分，之后缓存） */
    val paragraphs: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        splitParagraphs(content)
    }

    /** 朗读小片段（带段落归属，首次访问时才切分） */
    val clips: List<Clip> by lazy(LazyThreadSafetyMode.NONE) {
        buildClips(content)
    }

    /** 朗读文本列表（供 TTS 使用） */
    val sentences: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        clips.map { it.text }
    }

    /** 第 pi 段的第一个片段在 clips 里的下标 */
    fun firstClipOfParagraph(pi: Int): Int =
        clips.indexOfFirst { it.paragraphIndex == pi }.let { if (it < 0) 0 else it }

    /** 供代码里 copy 用（避免 data class 默认 copy 触发重算） */
    fun withContent(newIndex: Int = index, newTitle: String = title, newContent: String = content) =
        Chapter(newIndex, newTitle, newContent)

    override fun toString(): String = "Chapter($index, $title, ${content.length}字)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chapter) return false
        return index == other.index && title == other.title && content === other.content
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + title.hashCode()
        result = 31 * result + content.hashCode()
        return result
    }
}

/** 书架里的一本书 */
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    /** 内容来源：SAF 的 content:// URI，或内部存储路径 */
    val sourceUri: String,
    val chapters: List<Chapter>,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadChapter: Int = 0,
    val coverPath: String? = null
)

enum class BookFormat { TXT, EPUB, PDF }

// ---------------------------------------------------------------- 排版切分

/**
 * 按自然段切分。
 * 以换行为界；连续空行合并；去掉首尾空白。
 * 如果正文完全没有换行（旧数据），退化成一整段，保证不丢内容。
 */
fun splitParagraphs(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return text.replace("\r\n", "\n").replace('\r', '\n')
        .split('\n')
        .map { it.trim().trim('　') }
        .filter { it.isNotEmpty() }
}

// ---------------------------------------------------------------- 朗读切分

/** 切分参数 */
private const val MIN_CLIP_LEN = 8      // 少于这个字数就与下一段合并
private const val MAX_CLIP_LEN = 35     // 超过这个字数强制切

/**
 * 标点优先级（数字越小越优先切断）。
 * 顿号「、」**不参与切分** —— 它表示并列关系，切开会让朗读断断续续。
 */
private val PUNCT_PRIORITY: Map<Char, Int> = mapOf(
    '。' to 1, '！' to 1, '？' to 1, '…' to 1, '.' to 1, '!' to 1, '?' to 1,
    '；' to 2, ';' to 2, '：' to 2, ':' to 2,
    '，' to 3, ',' to 3
)

private const val CLOSING_CHARS = "”」』）】\"'》"

/**
 * 把整章正文切成朗读小片段，并记录每个片段所属的段落。
 *
 * 关键设计：**逐段切分**，每段独立切，段与段之间不合并。
 * 这样片段的 paragraphIndex 天然准确，不需要事后累加推测。
 *
 * 单段内的规则：
 *   1. 按标点断开（连续同类标点和收尾引号一并归入前一段）
 *   2. 不足 MIN_CLIP_LEN 的片段合并到下一段（段末的并入前一段）
 *   3. 超过 MAX_CLIP_LEN 的再在内部找标点切，找不到就按字数硬切
 */
fun buildClips(text: String): List<Clip> {
    if (text.isBlank()) return emptyList()
    val paragraphs = splitParagraphs(text)
    if (paragraphs.isEmpty()) return emptyList()

    val out = ArrayList<Clip>()
    paragraphs.forEachIndexed { pIdx, para ->
        splitParagraphClips(para).forEach { piece ->
            out.add(Clip(piece, pIdx))
        }
    }
    return out
}

/** 切分单个段落，返回片段文本列表（不涉及段落归属） */
fun splitToClips(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return splitParagraphClips(text)
}

private fun splitParagraphClips(para: String): List<String> {
    // 1. 按标点断开
    val pieces = ArrayList<String>()
    val buf = StringBuilder()
    var i = 0
    while (i < para.length) {
        val c = para[i]
        buf.append(c)
        if (c in PUNCT_PRIORITY) {
            var j = i + 1
            while (j < para.length && para[j] == c) {
                buf.append(para[j]); j++
            }
            while (j < para.length && para[j] in CLOSING_CHARS) {
                buf.append(para[j]); j++
            }
            val s = buf.toString()
            if (s.isNotBlank()) pieces.add(s)
            buf.setLength(0)
            i = j
            continue
        }
        i++
    }
    if (buf.isNotBlank()) pieces.add(buf.toString())

    // 2. 合并过短片段
    val merged = ArrayList<String>()
    var carry = StringBuilder()
    for (p in pieces) {
        carry.append(p)
        if (carry.toString().trim().length >= MIN_CLIP_LEN) {
            merged.add(carry.toString())
            carry = StringBuilder()
        }
    }
    if (carry.isNotBlank()) {
        if (merged.isNotEmpty()) {
            merged[merged.size - 1] = merged.last() + carry.toString()
        } else {
            merged.add(carry.toString())
        }
    }

    // 3. 切分过长片段
    val result = ArrayList<String>()
    for (p in merged) {
        if (p.trim().length <= MAX_CLIP_LEN) {
            result.add(p.trim())
        } else {
            result.addAll(splitLongClip(p).map { it.trim() })
        }
    }
    return result.filter { it.isNotBlank() }
}

/** 对超长片段再次切分 */
private fun splitLongClip(s: String): List<String> {
    val out = ArrayList<String>()
    var start = 0
    val n = s.length
    while (start < n) {
        var bestEnd = -1
        var bestPrio = Int.MAX_VALUE
        val limit = minOf(start + MAX_CLIP_LEN, n)
        var k = start + MIN_CLIP_LEN
        while (k < limit) {
            val prio = PUNCT_PRIORITY[s[k]]
            if (prio != null && prio < bestPrio) {
                bestPrio = prio
                bestEnd = k + 1
                if (prio == 1) break
            }
            k++
        }
        if (bestEnd > start) {
            out.add(s.substring(start, bestEnd))
            start = bestEnd
        } else {
            val end = minOf(start + MAX_CLIP_LEN, n)
            out.add(s.substring(start, end))
            start = end
        }
    }
    return out.filter { it.isNotBlank() }
}
