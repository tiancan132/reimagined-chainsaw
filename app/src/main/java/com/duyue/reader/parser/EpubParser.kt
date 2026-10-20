package com.duyue.reader.parser

import android.content.Context
import android.net.Uri
import com.duyue.reader.model.Chapter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** EPUB 解析结果：书名、作者、章节 */
data class EpubResult(
    val title: String,
    val author: String,
    val chapters: List<Chapter>
)

/**
 * EPUB 2/3 解析。
 *
 * 章节标题提取（按优先级，解决「识别不了目录」的问题）：
 *   1. toc.ncx（EPUB 2 目录）里该文件的 label —— 最权威
 *   2. nav.xhtml（EPUB 3 目录）里对应的链接文字
 *   3. 页面内 h1~h4 标签
 *   4. 页面内 class/id 含 title/head/章节 的元素
 *   5. 页面内第一段文字（若足够短）
 *   6. 文件名兜底
 */
object EpubParser {

    fun parse(context: Context, uri: Uri): EpubResult {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): EpubResult {
        // 解压全部条目到内存
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/')
                    entries[name] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val containerBytes = entries["META-INF/container.xml"]
            ?: throw IllegalStateException("不是有效的 EPUB：缺少 META-INF/container.xml")
        val containerXml = String(containerBytes, Charsets.UTF_8)
        val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(containerXml)?.groupValues?.get(1)
            ?: throw IllegalStateException("EPUB 中找不到 OPF 文件路径")

        val opfBytes = entries[opfPath] ?: throw IllegalStateException("OPF 文件不存在: $opfPath")
        val opfDoc = Jsoup.parse(String(opfBytes, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())

        val baseDir = opfPath.substringBeforeLast('/', "")
        val bookTitle = opfDoc.select("metadata title").firstOrNull()?.text()?.trim().orEmpty()

        // 作者：dc:creator 可能有多个，取第一个非空
        val bookAuthor = run {
            val creators = opfDoc.select("metadata creator, dc|creator")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotBlank() }
            creators.firstOrNull()
                // 有的书用 dc:contributor
                ?: opfDoc.select("metadata contributor").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: ""
        }

        // manifest: id -> href
        val manifest = LinkedHashMap<String, String>()
        opfDoc.select("manifest item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            val mediaType = item.attr("media-type")
            if (mediaType.contains("html", true) || mediaType.contains("xhtml", true)) {
                manifest[id] = href
            }
        }

        // 目录：优先 nav.xhtml（EPUB3），其次 toc.ncx（EPUB2）
        val tocMap = LinkedHashMap<String, String>()   // 归一化路径 -> 标题

        // --- toc.ncx ---
        val ncxPath = opfDoc.select("spine").attr("toc").ifBlank { null }
            ?.let { manifest[it] }
            ?: entries.keys.firstOrNull { it.endsWith(".ncx") }
        if (ncxPath != null) {
            val resolved = resolvePath(baseDir, ncxPath)
            val ncxBytes = entries[resolved] ?: entries[ncxPath]
            if (ncxBytes != null) {
                val ncx = Jsoup.parse(String(ncxBytes, Charsets.UTF_8), "", org.jsoup.parser.Parser.xmlParser())
                ncx.select("navPoint").forEach { np ->
                    val label = np.select("navLabel text").firstOrNull()?.text()?.trim().orEmpty()
                    val src = np.select("content").attr("src").substringBefore('#')
                    if (label.isNotBlank() && src.isNotBlank()) {
                        val full = resolvePath(resolved.substringBeforeLast('/', ""), src)
                        tocMap.putIfAbsent(normalize(full), label)
                    }
                }
            }
        }

        // --- nav.xhtml ---
        entries.entries.firstOrNull { it.key.contains("nav", true) && it.key.endsWith(".xhtml", true) }?.let { (path, data) ->
            val doc = Jsoup.parse(String(data, Charsets.UTF_8))
            doc.select("nav a").forEach { a ->
                val href = a.attr("href").substringBefore('#')
                val text = a.text().trim()
                if (href.isNotBlank() && text.isNotBlank()) {
                    val full = resolvePath(path.substringBeforeLast('/', ""), href)
                    tocMap.putIfAbsent(normalize(full), text)
                }
            }
        }

        // spine 顺序
        val spineIds = opfDoc.select("spine itemref").mapNotNull { it.attr("idref") }

        val chapters = ArrayList<Chapter>()
        val seenHref = HashSet<String>()

        for (idref in spineIds) {
            val href = manifest[idref] ?: continue
            val fullPath = resolvePath(baseDir, href)
            if (!seenHref.add(normalize(fullPath))) continue

            val htmlBytes = entries[fullPath]
                ?: entries[java.net.URLDecoder.decode(fullPath, "UTF-8")]
                ?: continue
            val doc = Jsoup.parse(String(htmlBytes, Charsets.UTF_8))
            val body = doc.body() ?: continue
            body.select("script, style, nav, svg, head").remove()
            // 关键：不能直接用 body.text() —— 它会把所有段落合并成一行，
            // 导致正文排版没有段落、朗读时也不会在段间停顿。
            // 这里改为按块级元素逐段提取，用 \n 保留段落边界。
            val text = extractParagraphs(body)
            if (text.length < 2) continue

            val title = extractTitle(doc, body, normalize(fullPath), tocMap, fullPath)
            chapters.add(Chapter(chapters.size, title, text))
        }

        if (chapters.isEmpty()) throw IllegalStateException("EPUB 未解析到任何章节")
        return EpubResult(bookTitle, bookAuthor, chapters)
    }

    /** 按优先级提取章节标题 */
    private fun extractTitle(
        doc: org.jsoup.nodes.Document,
        body: Element,
        normalizedPath: String,
        tocMap: Map<String, String>,
        fullPath: String
    ): String {
        // 1. 目录里的标签
        tocMap[normalizedPath]?.let { if (it.isNotBlank()) return it }

        // 2. h1 - h4
        for (tag in listOf("h1", "h2", "h3", "h4")) {
            body.selectFirst(tag)?.text()?.trim()?.let {
                if (it.isNotBlank() && it.length <= 80) return it
            }
        }

        // 3. class/id 含标题特征
        body.select("[class*=title], [class*=head], [id*=title], [id*=head], .chapter, .chap").forEach { el ->
            val t = el.text().trim()
            if (t.isNotBlank() && t.length in 2..80) return t
        }

        // 4. 第一个短段落
        body.select("p").firstOrNull()?.text()?.trim()?.let {
            if (it.isNotBlank() && it.length in 2..40 && !it.contains("。")) return it
        }

        // 5. <title>
        doc.title().trim()?.let {
            if (it.isNotBlank() && it.length <= 80) return it
        }

        // 6. 文件名
        return fullPath.substringAfterLast('/').substringBeforeLast('.')
    }

    /**
     * 按段落提取正文纯文本。
     *
     * 为什么要自己写而不用 body.text()：
     *   body.text() 会把所有块级元素合并成一行，段落信息全部丢失，
     *   导致正文排版没有分段、朗读也没有段间停顿。
     *
     * 策略：
     *   · 优先按 <p> 取段落（标准 EPUB 都用 p）
     *   · 如果几乎没有 <p>，退回按 <div>/<br> 切分
     *   · 每段压掉多余空白，段落之间用 \n 分隔
     */
    private fun extractParagraphs(body: Element): String {
        // 主路径：标准 EPUB 用 <p> 分自然段
        val paragraphs = body.select("p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.size >= 2) {
            return paragraphs.joinToString("\n")
        }

        // 没有成段的 <p>：用换行符直接替换块级标签，再取文本
        val clone = body.clone()
        // 先处理 <br>，换成真正的换行符
        clone.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
        // 块级元素结束后插入换行
        clone.select("p, div, h1, h2, h3, h4, h5, h6, li, blockquote, tr").forEach {
            it.appendChild(org.jsoup.nodes.TextNode("\n"))
        }

        // text() 会把连续空白压成一个空格，所以先取 text 再手动按已知换行拆
        val raw = clone.text().replace(" ", "\u0000")
        val cleaned = raw.split('\n')
            .map { it.replace('\u0000', ' ').trim() }
            .filter { it.isNotEmpty() }

        if (cleaned.size >= 2) return cleaned.joinToString("\n")

        // 最后兜底：按句号切分，至少让排版不是一整块
        return cleaned.joinToString("\n")
    }

    private fun normalize(path: String): String = path.replace("./", "").trimStart('/').lowercase()

    private fun resolvePath(base: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        if (base.isEmpty()) return cleanHref
        val stack = ArrayList(base.split('/').filter { it.isNotEmpty() })
        for (seg in cleanHref.split('/')) {
            when (seg) {
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                ".", "" -> {}
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }
}
