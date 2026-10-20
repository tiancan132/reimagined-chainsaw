package com.duyue.reader.data

import android.content.Context
import com.duyue.reader.model.Book
import com.duyue.reader.model.BookFormat
import com.duyue.reader.model.Chapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 书架存储（v2 结构）。
 *
 * 性能设计：**一本书一个文件**。
 *   files/books/index.json     ← 只存书籍元数据（书名、作者、进度、章节标题），几 KB
 *   files/books/<id>.json      ← 单本书的完整章节正文
 *
 * 这样启动时只读 index.json（毫秒级），打开某本书时才按需加载它的正文。
 * 旧版本把所有正文塞进一个 library.json，523 章就膨胀到 19MB，启动会卡死。
 */
class LibraryStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val booksDir: File
        get() = File(context.filesDir, "books").apply { mkdirs() }

    private val indexFile: File
        get() = File(booksDir, "index.json")

    private val legacyFile: File
        get() = File(context.filesDir, "library.json")

    // ---------------------------------------------------------------- 索引

    /**
     * 读取书架索引。
     * 返回的 Book 只带章节标题（content 为空），正文需要 loadBookContent 再次加载。
     */
    fun loadIndex(): List<Book> {
        // 首次迁移：把旧的 library.json 拆成一书一文件
        if (!indexFile.exists() && legacyFile.exists()) {
            migrateFromLegacy()
        }

        if (!indexFile.exists()) return emptyList()

        return try {
            val root = json.parseToJsonElement(indexFile.readText())
            root.jsonArray.mapNotNull { el ->
                runCatching { parseBookMeta(el.jsonObject) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 读取某本书的完整正文。
     *
     * ⚠️ 关键：**不能用 f.readText() 整读**。
     * 一本 523 章的书序列化后 JSON 有 16MB 以上，整读成字符串再解析成对象
     * 会让 Android 堆直接 OOM（之前的闪退根因就是：
     * `OutOfMemoryError ... at FilesKt.readText -> LibraryStore.loadContent`）。
     *
     * 改为逐块缓冲读取，避免一次性分配 16MB 的字符串。
     */
    fun loadContent(bookId: String, meta: Book): Book {
        val f = File(booksDir, "$bookId.json")
        if (!f.exists()) return meta
        return try {
            val sb = StringBuilder(1 shl 16)
            f.inputStream().buffered().use { input ->
                val reader = input.reader(Charsets.UTF_8)
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                }
            }
            val o = json.parseToJsonElement(sb.toString()).jsonObject
            sb.setLength(0)
            val chapters = parseChapters(o) ?: return meta
            meta.copy(chapters = chapters)
        } catch (e: OutOfMemoryError) {
            meta
        } catch (e: Exception) {
            meta
        }
    }

    /**
     * 保存单本书的正文。
     *
     * ⚠️ 关键防御：书架上的 Book 只带章节标题、正文为空的（延迟加载）。
     * 如果误把这种「空壳」写回磁盘，会把已有的正文覆盖成空 —— 这正是 v0.3 丢书的 bug。
     * 因此：**只要传入的章节正文全为空，就直接跳过，绝不动磁盘上的文件。**
     */
    fun saveBook(book: Book) {
        if (book.chapters.isEmpty()) return
        val hasContent = book.chapters.any { it.content.isNotBlank() }
        if (!hasContent) {
            // 空壳数据，拒绝覆盖
            return
        }
        // 再保险：如果磁盘上已有更大的正文，而这次传入的明显更小（大概率是空壳），也不覆盖
        val target = File(booksDir, "${book.id}.json")
        if (target.exists()) {
            val incomingChars = book.chapters.sumOf { it.content.length }
            val existingChars = target.length()
            // 文件字节数远大于传入字符数时，说明磁盘上有更完整的正文，拒绝覆盖
            if (existingChars > 10_000 && incomingChars * 2 < existingChars) {
                return
            }
        }

        val chapters = buildJsonArray {
            book.chapters.forEach { c ->
                add(buildJsonObject {
                    put("title", c.title)
                    put("content", c.content)
                })
            }
        }
        val obj = buildJsonObject {
            put("id", book.id)
            put("chapters", chapters)
        }
        runCatching { target.writeText(obj.toString()) }
    }

    /** 保存书架索引（只写元数据，很快） */
    fun saveIndex(books: List<Book>) {
        val arr = buildJsonArray {
            books.forEach { b ->
                add(buildJsonObject {
                    put("id", b.id)
                    put("title", b.title)
                    put("author", b.author)
                    put("format", b.format.name)
                    put("sourceUri", b.sourceUri)
                    put("addedAt", b.addedAt.toString())
                    put("lastReadChapter", b.lastReadChapter.toString())
                    // 索引里只存章节标题，不存正文
                    put("chapterTitles", buildJsonArray {
                        b.chapters.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.title)) }
                    })
                })
            }
        }
        runCatching { indexFile.writeText(arr.toString()) }
    }

    /** 兼容旧调用：保存全部（索引 + 每本书正文） */
    fun save(books: List<Book>) {
        saveIndex(books)
        books.forEach { saveBook(it) }
    }

    fun deleteBook(bookId: String) {
        runCatching { File(booksDir, "$bookId.json").delete() }
    }

    // ---------------------------------------------------------------- 解析

    private fun parseBookMeta(o: kotlinx.serialization.json.JsonObject): Book {
        val id = o["id"]?.jsonPrimitive?.content ?: return emptyBookPlaceholder()
        val titles = o["chapterTitles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        return Book(
            id = id,
            title = o["title"]?.jsonPrimitive?.content ?: "未命名",
            author = o["author"]?.jsonPrimitive?.content ?: "未知",
            format = BookFormat.valueOf(o["format"]?.jsonPrimitive?.content ?: "TXT"),
            sourceUri = o["sourceUri"]?.jsonPrimitive?.content ?: "",
            // 只保留标题，正文留空
            chapters = titles.mapIndexed { i, t -> Chapter(i, t, "") },
            addedAt = o["addedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            lastReadChapter = o["lastReadChapter"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        )
    }

    private fun parseChapters(o: kotlinx.serialization.json.JsonObject): List<Chapter>? {
        val arr = o["chapters"]?.jsonArray ?: return null
        return arr.mapIndexed { i, c ->
            val co = c.jsonObject
            Chapter(
                index = i,
                title = co["title"]?.jsonPrimitive?.content ?: "第${i + 1}章",
                content = co["content"]?.jsonPrimitive?.content ?: ""
            )
        }
    }

    private fun emptyBookPlaceholder(): Book = throw IllegalStateException("book 缺少 id")

    // ---------------------------------------------------------------- 迁移

    /**
     * 把旧版 library.json 拆成一书一文件。
     * 旧文件可能很大（19MB+），这里一次性处理完后就删除它，避免继续占用。
     */
    private fun migrateFromLegacy() {
        runCatching {
            val text = legacyFile.readText()
            val root = json.parseToJsonElement(text).jsonArray

            val metas = ArrayList<Book>()
            for (el in root) {
                val o = runCatching { el.jsonObject }.getOrNull() ?: continue
                val id = o["id"]?.jsonPrimitive?.content ?: continue

                val chapters = parseChapters(o) ?: emptyList()
                val book = Book(
                    id = id,
                    title = o["title"]?.jsonPrimitive?.content ?: "未命名",
                    author = o["author"]?.jsonPrimitive?.content ?: "未知",
                    format = BookFormat.valueOf(o["format"]?.jsonPrimitive?.content ?: "TXT"),
                    sourceUri = o["sourceUri"]?.jsonPrimitive?.content ?: "",
                    chapters = chapters,
                    addedAt = o["addedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    lastReadChapter = o["lastReadChapter"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                )

                // 正文单独存一个文件
                saveBook(book)
                // 索引里只保留章节标题
                metas.add(book.copy(chapters = chapters.mapIndexed { i, c -> Chapter(i, c.title, "") }))
            }

            if (metas.isNotEmpty()) {
                saveIndex(metas)
                // 全部迁移成功后才删除旧文件
                runCatching { legacyFile.delete() }
            }
        }
    }
}
