package com.duyue.reader.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * AI 章节预习 —— 调用 DeepSeek（OpenAI 兼容接口）。
 *
 * 设计：
 * - 流式输出（stream=true），用户能边看 AI 边生成，等待感低。
 * - 长章节自动截断/分段，避免超出上下文。
 * - 提示词针对「读前预习」优化：概括剧情、列人物、点出关键冲突，而不是复述原文。
 */
class AiSummaryClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 章节内容过长时截取，DeepSeek 上下文约 64K token，中文约 3-4 万字 */
    private fun trim(content: String, maxChars: Int = 12000): String =
        if (content.length <= maxChars) content
        else content.take(maxChars) + "\n\n……（本章篇幅较长，以上为前半部分内容）"

    private fun buildPrompt(bookTitle: String, chapterTitle: String, content: String): String {
        val body = trim(content)
        return """
你是一位资深的阅读陪伴助手。用户准备阅读下面这一章，但想先「预习」一下再读。

书名：《$bookTitle》
章节：$chapterTitle

【本章原文】
$body

请输出一份简明好读的预习摘要，严格使用以下格式（不要输出额外寒暄）：

## 一句话看本章
（用一句话说清这一章讲了什么，不超过60字）

## 主要内容
（用 3-5 条要点概括本章发生了什么，每条一两句话）

## 人物与关系
（列出本章出现的主要人物及其在本章中的举动/关系变化；若本章没有人物，写"无"）

## 阅读提示
（1-2 条：读这一章时值得留意的伏笔、冲突或情绪转折）

请注意：
1. 不要照抄原文，用你自己的话概括。
2. 不要剧透本章之后的情节。
3. 如果本章是序章/目录/纯说明性文字，如实说明即可。
""".trimIndent()
    }

    /** 流式生成：每收到一段文本就回调 onDelta */
    suspend fun summarizeStream(
        apiKey: String,
        bookTitle: String,
        chapterTitle: String,
        chapterContent: String,
        model: String = "deepseek-chat",
        onDelta: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", 0.5)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildPrompt(bookTitle, chapterTitle, chapterContent))
                })
            }
        }.toString()

        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val response: Response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                throw IllegalStateException("AI 请求失败 (${resp.code})：${friendlyError(resp.code, err)}")
            }
            val stream = resp.body?.byteStream() ?: throw IllegalStateException("无响应内容")
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        val el = Json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray
                            ?.firstOrNull()?.jsonObject
                            ?.get("delta")?.jsonObject
                            ?.get("content")
                        // 注意：JSON 里的 null 用 jsonPrimitive.content 会得到字符串 "null"，
                        // 必须显式判空，否则会把 "null" 当成正文推到界面上（之前的 bug）
                        if (el == null || el is kotlinx.serialization.json.JsonNull) null
                        else el.jsonPrimitive.content
                    }.getOrNull() ?: continue
                    if (delta.isNotEmpty() && delta != "null") onDelta(delta)
                }
            }
        }
    }

    /** 非流式版本（备用） */
    suspend fun summarize(
        apiKey: String,
        bookTitle: String,
        chapterTitle: String,
        chapterContent: String,
        model: String = "deepseek-chat"
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", false)
            put("temperature", 0.5)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildPrompt(bookTitle, chapterTitle, chapterContent))
                })
            }
        }.toString()

        val request = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException(friendlyError(resp.code, text))
            val root = Json.parseToJsonElement(text).jsonObject
            root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.content
                ?: ""
        }
    }

    /**
     * 拉取当前 API Key 可用的模型列表。
     * DeepSeek 提供 OpenAI 兼容接口：GET /models
     */
    suspend fun fetchModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.deepseek.com/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException(friendlyError(resp.code, text))
            }
            val root = Json.parseToJsonElement(text).jsonObject
            val arr = root["data"]?.jsonArray ?: return@use emptyList()
            arr.mapNotNull {
                runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
            }.filter { it.isNotBlank() }
        }
    }

    /**
     * 测试 Key + 模型是否可用。
     * 返回 (成功与否, 给用户看的提示)
     */
    suspend fun testConnection(
        apiKey: String,
        model: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext false to "请先填写 API Key"
        try {
            val payload = buildJsonObject {
                put("model", model.ifBlank { "deepseek-chat" })
                putJsonArray("messages") {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "你好")
                    })
                }
                put("max_tokens", 4)
            }.toString()

            val req = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    true to "连接成功，可使用「${model.ifBlank { "deepseek-chat" }}」"
                } else {
                    false to friendlyError(resp.code, text)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            false to "连接超时，请检查网络"
        } catch (e: Exception) {
            false to (e.message ?: "连接失败")
        }
    }

    /** 简单连通性测试 */
    suspend fun testKey(apiKey: String): Boolean =
        testConnection(apiKey, "deepseek-chat").first

    private fun friendlyError(code: Int, raw: String): String = when (code) {
        401 -> "API Key 无效，请检查是否填写正确"
        402 -> "账户余额不足，请前往 DeepSeek 平台充值"
        429 -> "请求过于频繁，请稍后再试"
        500, 502, 503 -> "DeepSeek 服务暂时不可用，请稍后重试"
        else -> if (raw.length > 200) raw.take(200) else raw
    }
}
