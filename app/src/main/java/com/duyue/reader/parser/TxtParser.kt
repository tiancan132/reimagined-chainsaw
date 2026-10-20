package com.duyue.reader.parser

import com.duyue.reader.model.Chapter

/**
 * TXT 分章。
 *
 * 覆盖中文小说站常见的全部标题写法：
 *   第一章 xxx / 第1章 xxx / 第 001 章 xxx / 第1章:xxx / 第一章、xxx
 *   第一卷 / 第2卷 / 卷一 / 卷1
 *   序章 序言 楔子 引子 前言 序幕 尾声 后记 终章 番外 完结感言
 *   正文 / 上卷 / 下卷 / 上篇 / 下篇
 *
 * 识别策略（分层，从松到紧）：
 *   1. 先找「第X章」「第X节」这类强特征行
 *   2. 再用「序章/楔子/番外」等特殊词
 *   3. 如果都没找到或太少（<3 个），退化为按标题行长度+独立性启发式
 *   4. 最后兜底：按固定字数切分
 */
object TxtParser {

    // 强特征：第X章 / 第X节 / 第X回（章、节、回才是真正的「章」；卷、集、部是分卷）
    private val chapterPattern = Regex(
        "^[\\s　]*第[\\s　]*[0-9０-９零一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰]+[\\s　]*[章節节回幕场]",
        RegexOption.IGNORE_CASE
    )

    // 正文里出现的「第X章节」不算标题
    private val bodyWordPattern = Regex(
        "^[\\s　]*第[\\s　]*[0-9０-９零一二三四五六七八九十百千万两]+[\\s　]*章节"
    )

    // 特殊章节名
    private val specialPattern = Regex(
        "^[\\s　]*(序章|序言|序幕|序|楔子|引子|前言|后记|尾声|终章|番外|终篇|完结感言|作者的话|正文)"
    )

    // 分卷标记：第X卷 / 卷二 / 卷1
    private val volumePattern = Regex(
        "^[\\s　]*(第[\\s　]*[0-9零一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰]+[\\s　]*[卷集部篇]|[卷集部篇][\\s　]*[0-9零一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰]+)"
    )

    /** 判断某一行是不是章节标题 */
    private fun isHeading(line: String): Boolean {
        val t = line.trim().trim('　')
        if (t.length < 2) return false
        // 标题行一般不会太长；太长的多半是正文里提到了「第X章」
        if (t.length > 40) return false
        // 不能以句末标点结尾（否则是正文句子）
        if (t.endsWith("。") || t.endsWith("，") || t.endsWith("！") ||
            t.endsWith("？") || t.endsWith("…") || t.endsWith("；")
        ) return false

        // 「第一章节」这类正文用词
        if (bodyWordPattern.containsMatchIn(t)) return false

        return chapterPattern.containsMatchIn(t) ||
            specialPattern.containsMatchIn(t) ||
            volumePattern.containsMatchIn(t)
    }

    fun parse(title: String, text: String): List<Chapter> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')

        // 找出所有标题行
        val hits = ArrayList<Int>()   // 行号
        lines.forEachIndexed { idx, line ->
            if (isHeading(line)) hits.add(idx)
        }

        // 标题太少：说明这本书可能是「无章节标记」的长文
        // 但也可能是只有几个分卷，此时仍按分卷切分更有意义
        if (hits.size < 2) {
            return splitByLength(title, normalized)
        }

        // 避免「目录页」造成大量重复标题：
        // 如果开头连续出现很多短标题行（典型的目录块），跳过它们
        var startIdx = 0
        val headWindow = hits.take(30)
        if (headWindow.size >= 5 && headWindow.last() < 120) {
            // 前 120 行里挤了 5 个以上标题 → 大概率是目录，跳过到最后一个后面
            startIdx = headWindow.last() + 1
        } else {
            startIdx = headWindow.firstOrNull() ?: 0
        }

        val validHits = hits.filter { it >= startIdx }

        if (validHits.size < 2) {
            return splitByLength(title, normalized)
        }

        val chapters = ArrayList<Chapter>()

        // 第一个标题之前的内容
        val prefaceEnd = validHits.first()
        if (prefaceEnd > 0) {
            val body = lines.subList(0, prefaceEnd).joinToString("\n").trim()
            if (body.length > 30) {
                chapters.add(Chapter(chapters.size, "前言", body))
            }
        }

        validHits.forEachIndexed { i, lineIdx ->
            val start = lineIdx + 1
            val end = if (i + 1 < validHits.size) validHits[i + 1] else lines.size
            val head = lines[lineIdx].trim().trim('　')
            val body = lines.subList(start, end).joinToString("\n").trim()
            chapters.add(Chapter(chapters.size, head, body))
        }

        // 过滤掉正文为空的章节（目录残留）
        val cleaned = chapters.filter { it.content.isNotBlank() || chapters.size <= 3 }
        return if (cleaned.size >= 2) cleaned.reindexed() else splitByLength(title, normalized)
    }

    /** 重新编号，保证 index 连续 */
    private fun List<Chapter>.reindexed(): List<Chapter> =
        mapIndexed { i, c -> Chapter(i, c.title, c.content) }

    /** 无章节标记时按固定字数切分 */
    private fun splitByLength(title: String, text: String, size: Int = 3000): List<Chapter> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Chapter>()
        var pos = 0
        var idx = 0
        while (pos < text.length) {
            var end = minOf(pos + size, text.length)
            if (end < text.length) {
                val para = text.indexOf('\n', end)
                if (para in 0 until end + 400) end = para
            }
            val body = text.substring(pos, end).trim()
            if (body.isNotEmpty()) {
                out.add(
                    Chapter(
                        idx,
                        if (idx == 0) "$title（第 1 部分）" else "$title（第 ${idx + 1} 部分）",
                        body
                    )
                )
                idx++
            }
            pos = end
        }
        return out
    }
}
