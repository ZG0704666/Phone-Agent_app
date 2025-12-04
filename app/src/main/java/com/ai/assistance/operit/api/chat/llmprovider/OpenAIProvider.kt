package com.ai.assistance.operit.api.chat.llmprovider

import android.util.Log
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.StreamingJsonXmlConverter
import com.ai.assistance.operit.util.TokenCacheManager
import com.ai.assistance.operit.util.exceptions.UserCancellationException
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.stream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI API格式的实现，支持标准OpenAI接口和兼容此格式的其他提供商
 * 
 * ## enableToolCall 参数说明
 * 
 * `enableToolCall` 用于启用/禁用 OpenAI Tool Call API 原生格式。
 * 
 * ### 工作原理
 * 
 * 当 `enableToolCall = true` 时，本Provider会执行双向格式转换：
 * 
 * 1. **发送请求前**：将内部XML格式的工具调用转换为OpenAI Tool Call格式
 *    - `<tool name="xxx"><param name="yyy">value</param></tool>` 
 *    - → `{"tool_calls": [{"function": {"name": "xxx", "arguments": "{\"yyy\": \"value\"}"}}]}`
 * 
 * 2. **接收响应后**：将API返回的Tool Call格式转换回XML格式
 *    - API返回的tool_calls对象 → XML格式
 *    - 保持上层代码对XML格式的兼容性
 * 
 * ### 历史记录处理
 * 
 * - **Assistant消息**：XML工具调用 → OpenAI `tool_calls` 字段
 * - **User消息**：XML `tool_result` → OpenAI `role: "tool"` 消息
 * - **tool_call_id追踪**：自动生成和匹配ID，确保工具调用与结果正确关联
 * 
 * ### 适用场景
 * 
 * - 使用支持原生Tool Call API的模型（GPT-4、Claude、Qwen等）
 * - 需要更结构化的工具调用处理
 * - 希望利用模型的自动工具选择功能
 * 
 * ### 注意事项
 * 
 * - 默认值为 `false`，需要显式启用
 * - 启用后会自动添加 `tools` 和 `tool_choice` 到请求体
 * - 流式响应中也支持增量工具调用数据的处理
 * 
 * @param enableToolCall 是否启用Tool Call API格式转换（默认false）
 */
open class OpenAIProvider(
    private val apiEndpoint: String,
    private val apiKeyProvider: ApiKeyProvider,
    val modelName: String,
    private val client: OkHttpClient,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val providerType: ApiProviderType = ApiProviderType.OPENAI,
    protected val supportsVision: Boolean = false, // 是否支持图片处理
    val enableToolCall: Boolean = false // 是否启用Tool Call接口
) : AIService {
    // private val client: OkHttpClient = HttpClientFactory.instance

    protected val JSON = "application/json; charset=utf-8".toMediaType()

    // 当前活跃的Call对象，用于取消流式传输
    private var activeCall: Call? = null
    @Volatile private var isManuallyCancelled = false

    /**
     * 不可重试的异常，通常由客户端错误（如4xx状态码）引起
     */
    class NonRetriableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    // Token缓存管理器
    private val tokenCacheManager = TokenCacheManager()

    // 公开token计数
    override val inputTokenCount: Int
        get() = tokenCacheManager.totalInputTokenCount
    override val outputTokenCount: Int
        get() = tokenCacheManager.outputTokenCount
    override val cachedInputTokenCount: Int
        get() = tokenCacheManager.cachedInputTokenCount

    // 供应商:模型标识符
    override val providerModel: String
        get() = "${providerType.name}:$modelName"

    // 重置token计数
    override fun resetTokenCounts() {
        tokenCacheManager.resetTokenCounts()
    }

    // 工具函数：分块打印大型文本日志
    protected fun logLargeString(tag: String, message: String, prefix: String = "") {
        // 设置单次日志输出的最大长度（Android日志上限约为4000字符）
        val maxLogSize = 3000

        // 如果消息长度超过限制，分块打印
        if (message.length > maxLogSize) {
            // 计算需要分多少块打印
            val chunkCount = message.length / maxLogSize + 1

            for (i in 0 until chunkCount) {
                val start = i * maxLogSize
                val end = minOf((i + 1) * maxLogSize, message.length)
                val chunkMessage = message.substring(start, end)

                // 打印带有编号的日志
                Log.d(tag, "$prefix Part ${i+1}/$chunkCount: $chunkMessage")
            }
        } else {
            // 消息长度在限制之内，直接打印
            Log.d(tag, "$prefix$message")
        }
    }

    // 取消当前流式传输
    override fun cancelStreaming() {
        isManuallyCancelled = true
        activeCall?.let {
            if (!it.isCanceled()) {
                it.cancel()
                Log.d("AIService", "已取消当前流式传输")
            }
        }
        activeCall = null
    }

    /**
     * 获取模型列表 注意：此方法直接调用ModelListFetcher获取模型列表
     * @return 模型列表结果
     */
    override suspend fun getModelsList(): Result<List<ModelOption>> {
        // 调用ModelListFetcher获取模型列表
        return ModelListFetcher.getModelsList(
            apiKey = apiKeyProvider.getApiKey(),
            apiEndpoint = apiEndpoint,
            apiProviderType = ApiProviderType.OPENAI // 默认为OpenAI类型
        )
    }

    override suspend fun testConnection(): Result<String> {
        return try {
            // 通过发送一条短消息来测试完整的连接、认证和API端点。
            // 这比getModelsList更可靠，因为它直接命中了聊天API。
            // 提供一个通用的系统提示，以防止某些需要它的模型出现错误。
            val testHistory = listOf("system" to "You are a helpful assistant.")
            val stream = sendMessage("Hi", testHistory, emptyList(), enableThinking = false, onTokensUpdated = { _, _, _ -> }, onNonFatalError = {})

            // 消耗流以确保连接有效。
            // 对 "Hi" 的响应应该很短，所以这会很快完成。
            stream.collect { _ -> }

            Result.success("连接成功！")
        } catch (e: Exception) {
            Log.e("AIService", "连接测试失败", e)
            Result.failure(IOException("连接测试失败: ${e.message}", e))
        }
    }

    // 解析服务器返回的内容，不再需要处理<think>标签
    private fun parseResponse(content: String): String {
        return content
    }

    // 创建请求体
    protected open fun createRequestBody(
            message: String,
            chatHistory: List<Pair<String, String>>,
            modelParameters: List<ModelParameter<*>> = emptyList(),
            enableThinking: Boolean = false,
            stream: Boolean = true,
            availableTools: List<ToolPrompt>? = null
    ): RequestBody {
        val jsonString = createRequestBodyInternal(message, chatHistory, modelParameters, stream, availableTools)
        return jsonString.toRequestBody(JSON)
    }

    /**
     * 内部方法，用于构建请求体的JSON字符串，以便子类可以重用和扩展。
     */
    protected fun createRequestBodyInternal(
        message: String,
        chatHistory: List<Pair<String, String>>,
        modelParameters: List<ModelParameter<*>> = emptyList(),
        stream: Boolean = true,
        availableTools: List<ToolPrompt>? = null
    ): String {
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream) // 根据stream参数设置

        // 添加已启用的模型参数
        for (param in modelParameters) {
            if (param.isEnabled) {
                when (param.valueType) {
                    com.ai.assistance.operit.data.model.ParameterValueType.INT ->
                            jsonObject.put(param.apiName, param.currentValue as Int)
                    com.ai.assistance.operit.data.model.ParameterValueType.FLOAT ->
                            jsonObject.put(param.apiName, param.currentValue as Float)
                    com.ai.assistance.operit.data.model.ParameterValueType.STRING ->
                            jsonObject.put(param.apiName, param.currentValue as String)
                    com.ai.assistance.operit.data.model.ParameterValueType.BOOLEAN ->
                            jsonObject.put(param.apiName, param.currentValue as Boolean)
                    com.ai.assistance.operit.data.model.ParameterValueType.OBJECT -> {
                        val raw = param.currentValue.toString().trim()
                        val parsed: Any? = try {
                            when {
                                raw.startsWith("{") -> JSONObject(raw)
                                raw.startsWith("[") -> JSONArray(raw)
                                else -> null
                            }
                        } catch (e: Exception) {
                            Log.w("AIService", "OBJECT参数解析失败: ${param.apiName}", e)
                            null
                        }
                        if (parsed != null) {
                            jsonObject.put(param.apiName, parsed)
                        } else {
                            // 解析失败则按字符串传递，避免崩溃
                            jsonObject.put(param.apiName, raw)
                        }
                    }
                }
                Log.d("AIService", "添加参数 ${param.apiName} = ${param.currentValue}")
            }
        }
        
        // 当工具为空时，将enableToolCall视为false
        val effectiveEnableToolCall = enableToolCall && availableTools != null && availableTools.isNotEmpty()
        
        // 如果启用Tool Call且传入了工具列表，添加tools定义
        var toolsJson: String? = null
        if (effectiveEnableToolCall) {
            val tools = buildToolDefinitions(availableTools!!)
            if (tools.length() > 0) {
                jsonObject.put("tools", tools)
                jsonObject.put("tool_choice", "auto") // 让模型自动决定是否使用工具
                toolsJson = tools.toString() // 保存工具定义用于token计算
                Log.d("AIService", "Tool Call已启用，添加了 ${tools.length()} 个工具定义")
            }
        }
        
        // 使用新的核心逻辑构建消息并获取token计数
        val (messagesArray, tokenCount) = buildMessagesAndCountTokens(message, chatHistory, effectiveEnableToolCall, toolsJson)
        jsonObject.put("messages", messagesArray)

        // 使用分块日志函数记录请求体（省略过长的tools字段）
        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        logLargeString("AIService", logJson.toString(4), "请求体: ")
        return jsonObject.toString()
    }

    /**
     * 构建content字段（可能是字符串或数组）
     * @param text 要处理的文本内容
     * @return 纯文本字符串或包含图片和文本的JSONArray
     */
    fun buildContentField(text: String): Any {
        // 如果模型不支持图片，移除所有图片链接，只保留文本
        if (!supportsVision) {
            if (ImageLinkParser.hasImageLinks(text)) {
                val textWithoutLinks = ImageLinkParser.removeImageLinks(text).trim()
                Log.w("AIService", "模型不支持图片处理，已移除图片链接。原始文本长度: ${text.length}, 处理后: ${textWithoutLinks.length}")
                return if (textWithoutLinks.isEmpty()) "[图片内容已省略，当前模型不支持图片处理]" else textWithoutLinks
            }
            return text
        }
        
        // 模型支持图片，正常处理图片链接
        if (ImageLinkParser.hasImageLinks(text)) {
            val imageLinks = ImageLinkParser.extractImageLinks(text)
            val textWithoutLinks = ImageLinkParser.removeImageLinks(text).trim()
            
            val contentArray = JSONArray()
            
            // 添加图片
            imageLinks.forEach { link ->
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:${link.mimeType};base64,${link.base64Data}")
                    })
                })
            }
            
            // 添加文本（如果有）
            if (textWithoutLinks.isNotEmpty()) {
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", textWithoutLinks)
                })
            }
            
            return contentArray
        } else {
            // 纯文本消息
            return text
        }
    }

    /**
     * 构建消息列表并计算token（核心逻辑）
     * @param message 用户消息
     * @param chatHistory 聊天历史
     * @param useToolCall 是否启用Tool Call格式转换（会根据工具可用性动态决定）
     * @param toolsJson 工具定义的JSON字符串，用于token计算
     * @return Pair(消息列表JSONArray, 输入token计数)
     */
    protected fun buildMessagesAndCountTokens(
            message: String,
            chatHistory: List<Pair<String, String>>,
            useToolCall: Boolean = false,
            toolsJson: String? = null
    ): Pair<JSONArray, Int> {
        val messagesArray = JSONArray()

        // 使用TokenCacheManager计算token数量（包含工具定义）
        val tokenCount = tokenCacheManager.calculateInputTokens(message, chatHistory, toolsJson)

        // 检查当前消息是否已经在历史记录的末尾（避免重复）
        val isMessageInHistory = chatHistory.isNotEmpty() && chatHistory.last().second == message
        
        // 如果消息已在历史中，只处理历史；否则需要处理历史+当前消息
        val effectiveHistory = if (isMessageInHistory) {
            chatHistory
        } else {
            chatHistory + ("user" to message)
        }

        // 追踪上一个assistant消息中的tool_call_ids，用于匹配tool结果
        val lastToolCallIds = mutableListOf<String>()

        // 添加聊天历史（包含当前消息如果它不在历史中）
        if (effectiveHistory.isNotEmpty()) {
            val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(effectiveHistory)
            val mergedHistory = mutableListOf<Pair<String, String>>()

            for ((role, content) in standardizedHistory) {
                if (mergedHistory.isNotEmpty() &&
                                role == mergedHistory.last().first &&
                                role != "system"
                ) {
                    val lastMessage = mergedHistory.last()
                    mergedHistory[mergedHistory.size - 1] =
                            Pair(lastMessage.first, lastMessage.second + "\n" + content)
                    Log.d("AIService", "合并连续的 $role 消息")
                } else {
                    mergedHistory.add(Pair(role, content))
                }
            }
            
            for ((role, content) in mergedHistory) {
                // 当启用Tool Call API时，转换XML格式的工具调用
                if (useToolCall) {
                    if (role == "assistant") {
                        // 解析assistant消息中的XML tool calls
                        val (textContent, toolCalls) = parseXmlToolCalls(content)
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)
                        
                        // 检查是否为空消息，如果是则填充 "[空消息]"
                        val effectiveContent = if (content.isBlank()) {
                            Log.d("AIService", "发现空的assistant消息，填充为[空消息]")
                            "[Empty]"
                        } else if (textContent.isNotEmpty()) {
                            textContent
                        } else {
                            null
                        }
                        
                        if (effectiveContent != null) {
                            historyMessage.put("content", buildContentField(effectiveContent))
                        } else {
                            historyMessage.put("content", null)
                        }
                        if (toolCalls != null && toolCalls.length() > 0) {
                            historyMessage.put("tool_calls", toolCalls)
                            // 记录这些tool_call_ids供后续tool消息使用
                            lastToolCallIds.clear()
                            for (i in 0 until toolCalls.length()) {
                                lastToolCallIds.add(toolCalls.getJSONObject(i).getString("id"))
                            }
                        }
                        messagesArray.put(historyMessage)
                    } else if (role == "user") {
                        // 解析user消息中的XML tool_result
                        val (textContent, toolResults) = parseXmlToolResults(content)
                        
                        // 标记是否成功转换了tool_result
                        var hasConvertedToolResults = false
                        
                        // 如果有tool results，需要添加为tool角色的消息
                        if (toolResults != null && toolResults.isNotEmpty() && lastToolCallIds.isNotEmpty()) {
                            // 只转换有对应tool_call_id的tool_result
                            val validCount = minOf(toolResults.size, lastToolCallIds.size)
                            
                            // 转换有效的tool_result
                            for (index in 0 until validCount) {
                                val (_, resultContent) = toolResults[index]
                                val toolMessage = JSONObject()
                                toolMessage.put("role", "tool")
                                toolMessage.put("tool_call_id", lastToolCallIds[index])
                                toolMessage.put("content", resultContent)
                                messagesArray.put(toolMessage)
                                Log.d("AIService", "历史XML→ToolResult: ID=${lastToolCallIds[index]}, content length=${resultContent.length}")
                                hasConvertedToolResults = true
                            }
                            
                            // 如果有多余的tool_result，记录警告
                            if (toolResults.size > validCount) {
                                Log.w("AIService", "发现多余的tool_result: ${toolResults.size} results vs ${lastToolCallIds.size} tool_calls，忽略多余的${toolResults.size - validCount}个")
                            }
                            
                            // 使用后清空
                            lastToolCallIds.clear()
                        }
                        
                        // 如果还有其他文本内容，添加为user消息
                        if (textContent.isNotEmpty()) {
                            val historyMessage = JSONObject()
                            historyMessage.put("role", role)
                            historyMessage.put("content", buildContentField(textContent))
                            messagesArray.put(historyMessage)
                            Log.d("AIService", "历史user消息有剩余文本: length=${textContent.length}, preview=${textContent.take(100)}")
                        } else if (!hasConvertedToolResults) {
                            // 如果没有转换任何tool_result，保留原始content
                            val historyMessage = JSONObject()
                            historyMessage.put("role", role)
                            historyMessage.put("content", buildContentField(content))
                            messagesArray.put(historyMessage)
                            Log.d("AIService", "历史user消息无tool_result转换，保留原始内容")
                        } else {
                            Log.d("AIService", "历史user消息全是tool_result，无剩余文本")
                        }
                    } else {
                        // system等其他角色正常处理
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)
                        historyMessage.put("content", buildContentField(content))
                        messagesArray.put(historyMessage)
                    }
                } else {
                    // 不启用Tool Call API时，保持原样
                    val historyMessage = JSONObject()
                    historyMessage.put("role", role)
                    
                    // 检查assistant角色的空消息
                    val effectiveContent = if (role == "assistant" && content.isBlank()) {
                        Log.d("AIService", "发现空的assistant消息，填充为[空消息]")
                        "[Empty]"
                    } else {
                        content
                    }
                    
                    historyMessage.put("content", buildContentField(effectiveContent))
                    messagesArray.put(historyMessage)
                }
            }
        }

        return Pair(messagesArray, tokenCount)
    }

    override suspend fun calculateInputTokens(
            message: String,
            chatHistory: List<Pair<String, String>>,
            availableTools: List<ToolPrompt>?
    ): Int {
        // 构建工具定义的JSON字符串
        val toolsJson = if (enableToolCall && availableTools != null && availableTools.isNotEmpty()) {
            val tools = buildToolDefinitions(availableTools)
            if (tools.length() > 0) tools.toString() else null
        } else {
            null
        }
        // 使用TokenCacheManager计算token数量
        return tokenCacheManager.calculateInputTokens(message, chatHistory, toolsJson)
    }

    // ==================== Tool Call 支持 ====================
    
    /**
     * 从ToolPrompt列表构建Tool Call的JSON Schema定义
     */
    fun buildToolDefinitions(toolPrompts: List<ToolPrompt>): JSONArray {
        val tools = JSONArray()
        
        for (tool in toolPrompts) {
            tools.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    // 组合description和details作为完整描述
                    val fullDescription = if (tool.details.isNotEmpty()) {
                        "${tool.description}\n${tool.details}"
                    } else {
                        tool.description
                    }
                    put("description", fullDescription)
                    
                    // 只使用结构化参数
                    val parametersSchema = buildSchemaFromStructured(tool.parametersStructured ?: emptyList())
                    put("parameters", parametersSchema)
                })
            })
        }
        
        return tools
    }
    
    /**
     * 从结构化参数构建JSON Schema
     */
    private fun buildSchemaFromStructured(params: List<com.ai.assistance.operit.data.model.ToolParameterSchema>): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }
        
        val properties = JSONObject()
        val required = JSONArray()
        
        for (param in params) {
            properties.put(param.name, JSONObject().apply {
                put("type", param.type)
                put("description", param.description)
                if (param.default != null) {
                    put("default", param.default)
                }
            })
            
            if (param.required) {
                required.put(param.name)
            }
        }
        
        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }
        
        return schema
    }
    
    /**
     * 解析ToolPrompt的parameters字符串为JSON Schema格式
     * 例如: "path (file path), recursive (boolean, default false)" 
     * 转换为 OpenAI JSON Schema
     */
    private fun parseParametersToSchema(parametersString: String): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
        }
        
        if (parametersString.isEmpty()) {
            schema.put("properties", JSONObject())
            return schema
        }
        
        val properties = JSONObject()
        val required = JSONArray()
        
        // 按逗号分割参数
        val params = parametersString.split(",").map { it.trim() }
        
        for (param in params) {
            if (param.isEmpty()) continue
            
            // 提取参数名和描述 "param_name (description)"
            val nameMatch = Regex("^([\\w_]+)\\s*\\((.+)\\)$").find(param)
            if (nameMatch != null) {
                val paramName = nameMatch.groupValues[1]
                val paramDesc = nameMatch.groupValues[2].trim()
                
                // 判断类型和是否可选
                val isOptional = paramDesc.contains("optional", ignoreCase = true) || 
                                 paramDesc.contains("default", ignoreCase = true)
                val paramType = when {
                    paramDesc.contains("boolean", ignoreCase = true) -> "boolean"
                    paramDesc.contains("number", ignoreCase = true) || 
                    paramDesc.contains("integer", ignoreCase = true) -> "integer"
                    else -> "string"
                }
                
                properties.put(paramName, JSONObject().apply {
                    put("type", paramType)
                    put("description", paramDesc)
                })
                
                if (!isOptional) {
                    required.put(paramName)
                }
            }
        }
        
        schema.put("properties", properties)
        if (required.length() > 0) {
            schema.put("required", required)
        }
        
        return schema
    }
    
    /**
     * 将API返回的tool_calls转换为XML格式
     * 这样上层代码无需修改，继续使用XML解析逻辑
     * @param toolCalls tool_calls JSON数组
     * @param isStreaming 是否为流式响应（流式响应中tool_calls是增量的）
     */
    private fun convertToolCallsToXml(toolCalls: JSONArray, isStreaming: Boolean = false): String {
        val xml = StringBuilder()
        
        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.getJSONObject(i)
            val function = toolCall.optJSONObject("function") ?: continue
            
            // 流式响应中，name和arguments可能不在同一个delta中
            val name = function.optString("name", "")
            if (name.isEmpty()) {
                // 如果没有name，说明这是增量更新，跳过
                continue
            }
            
            val argumentsJson = function.optString("arguments", "")
            
            // 解析参数JSON
            val params = if (argumentsJson.isNotEmpty()) {
                try {
                    JSONObject(argumentsJson)
                } catch (e: Exception) {
                    Log.w("OpenAIProvider", "Failed to parse tool arguments: $argumentsJson", e)
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            
            // 构建XML格式
            xml.append("<tool name=\"$name\">")
            
            // 添加所有参数
            val keys = params.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = params.get(key)
                // 必须对值进行XML转义，否则会破坏XML结构
                val escapedValue = escapeXml(value.toString())
                xml.append("\n<param name=\"$key\">$escapedValue</param>")
            }
            
            xml.append("\n</tool>\n")
        }
        
        return xml.toString()
    }

    /**
     * XML转义/反转义工具
     */
    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&apos;")
        }
        
        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&amp;", "&")
        }
    }
    
    // 向后兼容的快捷方法
    private fun escapeXml(text: String) = XmlEscaper.escape(text)
    
    /**
     * 字符串非空且非"null"检查
     */
    private fun String.isNotNullOrEmpty() = this.isNotEmpty() && this != "null"
    
    /**
     * Tool Call流式输出状态管理
     */
    private data class ToolCallState(
        val emitted: MutableMap<Int, Boolean> = mutableMapOf(),
        val nameEmitted: MutableMap<Int, Boolean> = mutableMapOf(),
        val parser: MutableMap<Int, StreamingJsonXmlConverter> = mutableMapOf(),
        val closed: MutableMap<Int, Boolean> = mutableMapOf()
    ) {
        fun getParser(index: Int) = parser.getOrPut(index) { StreamingJsonXmlConverter() }
        
        fun clear() {
            emitted.clear()
            nameEmitted.clear()
            parser.clear()
            closed.clear()
        }
    }
    
    /**
     * 流式内容发送辅助类
     */
    private inner class StreamEmitter(
        private val receivedContent: StringBuilder,
        private val emit: suspend (String) -> Unit,
        private val onTokensUpdated: suspend (Int, Int, Int) -> Unit
    ) {
        suspend fun emitContent(content: String) {
            if (content.isNotNullOrEmpty()) {
                emit(content)
                receivedContent.append(content)
                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(content))
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
            }
        }
        
        suspend fun emitThinkContent(thinkContent: String, tag: String = "think") {
            if (thinkContent.isNotNullOrEmpty()) {
                val wrapped = "<$tag>$thinkContent</$tag>"
                emit(wrapped)
                receivedContent.append(wrapped)
                tokenCacheManager.addOutputTokens(ChatUtils.estimateTokenCount(thinkContent))
                onTokensUpdated(
                    tokenCacheManager.totalInputTokenCount,
                    tokenCacheManager.cachedInputTokenCount,
                    tokenCacheManager.outputTokenCount
                )
            }
        }
        
        suspend fun emitTag(tag: String) {
            emit(tag)
            receivedContent.append(tag)
        }
        
        /**
         * 处理 StreamingJsonXmlConverter 事件，转换为 XML 输出
         */
        suspend fun handleJsonEvents(events: List<StreamingJsonXmlConverter.Event>) {
            events.forEach { event ->
                when (event) {
                    is StreamingJsonXmlConverter.Event.Tag -> emitTag(event.text)
                    is StreamingJsonXmlConverter.Event.Content -> emitContent(event.text)
                }
            }
        }
    }
    
    /**
     * 创建 Tool Call 累积对象
     */
    private fun createToolCallAccumulator(index: Int): JSONObject {
        return JSONObject().apply {
            put("index", index)
            put("id", "")
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "")
                put("arguments", "")
            })
        }
    }
    
    /**
     * 检查是否已被取消，如果是则抛出异常
     */
    private fun checkCancellation(exception: Exception? = null) {
        if (isManuallyCancelled) {
            Log.d("AIService", "请求被用户取消，停止重试。")
            throw UserCancellationException("请求已被用户取消", exception)
        }
    }
    
    /**
     * 处理可重试错误的统一逻辑
     */
    private suspend fun handleRetryableError(
        exception: Exception,
        retryCount: Int,
        maxRetries: Int,
        errorType: String,
        errorMessage: String,
        onNonFatalError: suspend (String) -> Unit
    ): Int {
        checkCancellation(exception)
        
        val newRetryCount = retryCount + 1
        if (newRetryCount >= maxRetries) {
            Log.e("AIService", "【发送消息】$errorType 且达到最大重试次数", exception)
            throw IOException(errorMessage)
        }
        
        Log.w("AIService", "【发送消息】$errorType，正在进行第 $newRetryCount 次重试...", exception)
        onNonFatalError("【$errorType，正在进行第 $newRetryCount 次重试...】")
        delay(1000L * (1 shl (newRetryCount - 1)))
        
        return newRetryCount
    }

    
    /**
     * 解析XML格式的tool调用，转换为OpenAI Tool Call格式
     * @return Pair<文本内容, tool_calls数组>
     */
    fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        val toolPattern = Regex("<tool\\s+name=\"([^\"]+)\">([\\s\\S]*?)</tool>", RegexOption.MULTILINE)
        val matches = toolPattern.findAll(content)
        
        if (!matches.any()) {
            return Pair(content, null)
        }
        
        val toolCalls = JSONArray()
        var textContent = content
        var callIndex = 0
        
        matches.forEach { match ->
            val toolName = match.groupValues[1]
            val toolBody = match.groupValues[2]
            
            // 解析参数
            val paramPattern = Regex("<param\\s+name=\"([^\"]+)\">([\\s\\S]*?)</param>")
            val params = JSONObject()
            
            paramPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                params.put(paramName, paramValue)
            }
            
            // 构建tool_call对象
            // 使用工具名和参数的哈希生成确定性ID
            val callId = "call_${toolName}_${params.toString().hashCode().toString(16)}_$callIndex"
            toolCalls.put(JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", params.toString())
                })
            })
            
            callIndex++
            Log.d("AIService", "XML→ToolCall: $toolName -> ID: $callId")
            
            // 从文本内容中移除tool标签
            textContent = textContent.replace(match.value, "")
        }
        
        return Pair(textContent.trim(), toolCalls)
    }
    
    /**
     * 解析XML格式的tool_result，转换为OpenAI Tool消息格式
     * @return List<Pair<tool_call_id, result_content>>
     */
    fun parseXmlToolResults(content: String): Pair<String, List<Pair<String, String>>?> {
        // 匹配带属性的tool_result标签，例如: <tool_result name="..." status="...">...</tool_result>
        val resultPattern = Regex("<tool_result[^>]*>([\\s\\S]*?)</tool_result>", RegexOption.MULTILINE)
        val matches = resultPattern.findAll(content)
        
        if (!matches.any()) {
            return Pair(content, null)
        }
        
        val results = mutableListOf<Pair<String, String>>()
        var textContent = content
        var resultIndex = 0
        
        matches.forEach { match ->
            // 提取<content>标签内的内容，如果有的话
            val fullContent = match.groupValues[1].trim()
            val contentPattern = Regex("<content>([\\s\\S]*?)</content>", RegexOption.MULTILINE)
            val contentMatch = contentPattern.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            
            // 生成一个tool_call_id（这里需要与之前的call对应，但因为历史记录可能不完整，我们使用索引）
            results.add(Pair("call_result_${resultIndex}", resultContent))
            
            // 从文本内容中移除tool_result标签（包括前后的空白符）
            textContent = textContent.replace(match.value, "").trim()
            
            Log.d("AIService", "解析tool_result #$resultIndex, content length=${resultContent.length}")
            resultIndex++
        }
        
        // trim 确保移除所有空白字符
        return Pair(textContent.trim(), results)
    }

    // 创建请求
    private suspend fun createRequest(requestBody: RequestBody): Request {
        val currentApiKey = apiKeyProvider.getApiKey()
        val builder = Request.Builder()
                .url(EndpointCompleter.completeEndpoint(apiEndpoint))
                .addHeader("Authorization", "Bearer $currentApiKey")
                .addHeader("Content-Type", "application/json")

        // 添加自定义请求头
        customHeaders.forEach { (key, value) ->
            builder.addHeader(key, value)
        }

        val request = builder.post(requestBody).build()
        logLargeString("AIService", "请求头: \n${request.headers}")
        return request
    }

    override suspend fun sendMessage(
            message: String,
            chatHistory: List<Pair<String, String>>,
            modelParameters: List<ModelParameter<*>>,
            enableThinking: Boolean,
            stream: Boolean,
            availableTools: List<ToolPrompt>?,
            onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
            onNonFatalError: suspend (error: String) -> Unit
    ): Stream<String> = stream {
        isManuallyCancelled = false
        // 重置输出token计数（输入token由TokenCacheManager管理）
        tokenCacheManager.addOutputTokens(-tokenCacheManager.outputTokenCount)
        onTokensUpdated(tokenCacheManager.totalInputTokenCount, tokenCacheManager.cachedInputTokenCount, tokenCacheManager.outputTokenCount)

        Log.d(
                "AIService",
                "【发送消息】开始处理sendMessage请求，消息长度: ${message.length}，历史记录数量: ${chatHistory.size}"
        )

        val maxRetries = 3
        var retryCount = 0
        var lastException: Exception? = null

        // 用于保存已接收到的内容，以便在重试时使用
        val receivedContent = StringBuilder()

        while (retryCount < maxRetries) {
            // 在循环开始时检查是否已被取消
            checkCancellation()
            
            try {
                // 如果是重试，我们需要构建一个新的请求
                val currentMessage: String
                val currentHistory: List<Pair<String, String>>

                if (retryCount > 0 && receivedContent.isNotEmpty()) {
                    Log.d("AIService", "【重试】准备续写请求，已接收内容长度: ${receivedContent.length}")
                    // 在用户消息后附加续写指令
                    currentMessage = message + "\n\n[SYSTEM NOTE] The previous response was cut off by a network error. You MUST continue from the exact point of interruption. Do not repeat any content. If you were in the middle of a code block or XML tag, complete it. Just output the text that would have come next."
                    // 将已接收的内容作为AI的上一条消息
                    val resumeHistory = chatHistory.toMutableList()
                    resumeHistory.add("assistant" to receivedContent.toString())
                    currentHistory = resumeHistory
                } else {
                    currentMessage = message
                    currentHistory = chatHistory
                }


                Log.d(
                        "AIService",
                        "【发送消息】准备构建请求体，模型参数数量: ${modelParameters.size}，已启用参数: ${modelParameters.count { it.isEnabled }}"
                )
                // 直接传递原始历史记录给createRequestBody，让具体的Provider决定如何处理（例如Deepseek需要保留<think>标签）
                val requestBody = createRequestBody(currentMessage, currentHistory, modelParameters, enableThinking, stream, availableTools)
                onTokensUpdated(tokenCacheManager.totalInputTokenCount, tokenCacheManager.cachedInputTokenCount, tokenCacheManager.outputTokenCount)
                val request = createRequest(requestBody)
                Log.d("AIService", "【发送消息】请求体构建完成，目标模型: $modelName，API端点: $apiEndpoint")

                Log.d("AIService", "【发送消息】准备连接到AI服务...")

                // 创建Call对象并保存到activeCall中，以便可以取消
                val call = client.newCall(request)
                activeCall = call

                Log.d("AIService", "【发送消息】正在建立连接到服务器...")

                // 确保在IO线程执行网络请求
                Log.d("AIService", "【发送消息】切换到IO线程执行网络请求")
                val response = withContext(Dispatchers.IO) { call.execute() }

                try {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "No error details"
                        Log.e("AIService", "【发送消息】API请求失败，状态码: ${response.code}，错误信息: $errorBody")
                        // 对于4xx这类明确的客户端错误，直接抛出，不进行重试
                        if (response.code in 400..499) {
                            throw NonRetriableException("API请求失败，状态码: ${response.code}，错误信息: $errorBody")
                        }
                        // 对于5xx等服务端错误，允许重试
                        throw IOException("API请求失败，状态码: ${response.code}，错误信息: $errorBody")
                    }

                    Log.d("AIService", "【发送消息】连接成功(状态码: ${response.code})，准备处理响应...")
                    val responseBody = response.body ?: throw IOException("API响应为空")

                    // 根据stream参数处理响应
                    if (stream) {
                        // 处理流式响应
                        withContext(Dispatchers.IO) {
                            Log.d("AIService", "【发送消息】开始读取流式响应")
                            val reader = responseBody.charStream().buffered()
                        var currentContent = StringBuilder()
                        var isFirstResponse = true
                        var wasCancelled = false
                        var chunkCount = 0
                        var lastLogTime = System.currentTimeMillis()

                        // 跟踪思考内容的状态
                        var isInReasoningMode = false
                        var hasEmittedThinkStart = false
                        
                        // 累积流式Tool Call数据（按index存储）
                        val accumulatedToolCalls = mutableMapOf<Int, JSONObject>()
                        // 追踪每个tool call的流式输出状态
                        val toolCallState = ToolCallState()
                        // 追踪上一个处理的工具index，用于检测工具切换
                        var lastProcessedToolIndex: Int? = null
                        // 流式内容发送器
                        val emitter = StreamEmitter(receivedContent, ::emit, onTokensUpdated)

                        try {
                            reader.useLines { lines ->
                                lines.forEach { line ->
                                    // 如果call已被取消，提前退出
                                    if (activeCall?.isCanceled() == true) {
                                        Log.d("AIService", "【发送消息】流式传输已被取消，提前退出处理")
                                        wasCancelled = true
                                        return@forEach
                                    }

                                    // 兼容 "data:" 和 "data: " 两种格式
                                    if (line.startsWith("data:")) {
                                        val data = line.substring(5).trim() // 跳过 "data:"
                                        if (data != "[DONE]") {
                                            chunkCount++
                                            // 每10个块或500ms记录一次日志
                                            val currentTime = System.currentTimeMillis()
                                            if (chunkCount % 10 == 0 ||
                                                            currentTime - lastLogTime > 500
                                            ) {
                                                // Log.d("AIService", "【发送消息】已处理数据块: $chunkCount")
                                                lastLogTime = currentTime
                                            }

                                            try {
                                                val jsonResponse = JSONObject(data)
                                                val choices = jsonResponse.getJSONArray("choices")

                                                if (choices.length() > 0) {
                                                    val choice = choices.getJSONObject(0)

                                                    // 处理delta格式（流式响应）
                                                    val delta = choice.optJSONObject("delta")
                                                    if (delta != null) {
                                                        // 累积流式Tool Call数据并流式输出
                                                        val toolCallsDeltas = delta.optJSONArray("tool_calls")
                                                        if (toolCallsDeltas != null && toolCallsDeltas.length() > 0 && enableToolCall) {
                                                            // 如果正在思考模式，收到工具调用时应先关闭思考标签
                                                            if (isInReasoningMode) {
                                                                isInReasoningMode = false
                                                                emitter.emitTag("</think>")
                                                                // 重置标记，以便后续如果有思考内容可以再次输出<think>
                                                                hasEmittedThinkStart = false
                                                            }

                                                            for (i in 0 until toolCallsDeltas.length()) {
                                                                val deltaCall = toolCallsDeltas.getJSONObject(i)
                                                                val index = deltaCall.optInt("index", -1)
                                                                if (index < 0) continue
                                                                
                                                                // 检测工具切换：如果index变化了，说明前一个工具已完成
                                                                if (lastProcessedToolIndex != null && lastProcessedToolIndex != index) {
                                                                    val prevIndex = lastProcessedToolIndex!!
                                                                    // 输出前一个工具的结束标签
                                                                    if (toolCallState.closed[prevIndex] != true && toolCallState.nameEmitted[prevIndex] == true) {
                                                                        val events = toolCallState.getParser(prevIndex).flush()
                                                                        emitter.handleJsonEvents(events)
                                                                        emitter.emitTag("\n</tool>")
                                                                        toolCallState.closed[prevIndex] = true
                                                                        Log.d("AIService", "检测到工具切换，关闭前一个工具 index=$prevIndex")
                                                                    }
                                                                }
                                                                lastProcessedToolIndex = index
                                                                
                                                                // 获取或创建该index的累积对象
                                                                val accumulated = accumulatedToolCalls.getOrPut(index) {
                                                                    createToolCallAccumulator(index)
                                                                }
                                                                
                                                                // 更新id和type
                                                                deltaCall.optString("id", "").let { if (it.isNotEmpty()) accumulated.put("id", it) }
                                                                deltaCall.optString("type", "").let { if (it.isNotEmpty()) accumulated.put("type", it) }
                                                                
                                                                // 累积function字段
                                                                val deltaFunction = deltaCall.optJSONObject("function")
                                                                if (deltaFunction != null) {
                                                                    val accFunction = accumulated.getJSONObject("function")
                                                                    val name = deltaFunction.optString("name", "")
                                                                    if (name.isNotEmpty()) {
                                                                        accFunction.put("name", name)
                                                                        
                                                                        // 【流式输出】当收到工具名时，立即输出开始标签
                                                                        if (toolCallState.nameEmitted[index] != true) {
                                                                            val toolStartTag = if (toolCallState.emitted[index] != true) {
                                                                                toolCallState.emitted[index] = true
                                                                                "\n<tool name=\"$name\">"
                                                                            } else {
                                                                                ""
                                                                            }
                                                                            if (toolStartTag.isNotEmpty()) {
                                                                                emitter.emitTag(toolStartTag)
                                                                            }
                                                                            toolCallState.nameEmitted[index] = true
                                                                        }
                                                                    }
                                                                    val args = deltaFunction.optString("arguments", "")
                                                                    if (args.isNotEmpty()) {
                                                                        val currentArgs = accFunction.optString("arguments", "")
                                                                        accFunction.put("arguments", currentArgs + args)
                                                                        
                                                                        // 【流式输出】解析并流式输出参数（包括参数值的增量）
                                                                        val events = toolCallState.getParser(index).feed(args)
                                                                        emitter.handleJsonEvents(events)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        
                                                        // 检查finish_reason，如果是tool_calls，输出最后一个工具的结束标签
                                                        val finishReason = choice.optString("finish_reason", "")
                                                        if (finishReason == "tool_calls" && lastProcessedToolIndex != null) {
                                                            val index = lastProcessedToolIndex!!
                                                            
                                                            // 输出最后一个工具的结束标签（如果还没关闭）
                                                            if (toolCallState.closed[index] != true && toolCallState.nameEmitted[index] == true) {
                                                                // 最后一次尝试输出任何遗漏的参数增量
                                                                val events = toolCallState.getParser(index).flush()
                                                                emitter.handleJsonEvents(events)
                                                                
                                                                // 输出工具结束标签
                                                                emitter.emitTag("\n</tool>")
                                                                toolCallState.closed[index] = true
                                                                Log.d("AIService", "Tool Call流式完成（最后一个工具 index=$index）")
                                                            }
                                                            
                                                            onTokensUpdated(tokenCacheManager.totalInputTokenCount, tokenCacheManager.cachedInputTokenCount, tokenCacheManager.outputTokenCount)
                                                            
                                                            // 清空累积器
                                                            accumulatedToolCalls.clear()
                                                            lastProcessedToolIndex = null
                                                        }
                                                        
                                                        // 检查是否有思考内容
                                                        val reasoningContent =
                                                                delta.optString(
                                                                        "reasoning_content",
                                                                        ""
                                                                )
                                                        val regularContent =
                                                                delta.optString("content", "")

                                                        // 处理思考内容
                                                        if (reasoningContent.isNotNullOrEmpty()) {
                                                            if (!isInReasoningMode) {
                                                                isInReasoningMode = true
                                                                if (!hasEmittedThinkStart) {
                                                                    emitter.emitTag("<think>")
                                                                    hasEmittedThinkStart = true
                                                                }
                                                            }
                                                            emitter.emitContent(reasoningContent)
                                                        }
                                                        // 处理常规内容
                                                        else if (regularContent.isNotNullOrEmpty()) {
                                                            // 如果之前在思考模式，现在切换到了常规内容，需要关闭思考标签
                                                            if (isInReasoningMode) {
                                                                isInReasoningMode = false
                                                                emitter.emitTag("</think>")
                                                                hasEmittedThinkStart = false
                                                            }

                                                            // 当收到第一个有效内容时，标记不再是首次响应
                                                            if (isFirstResponse) {
                                                                isFirstResponse = false
                                                                Log.d("AIService", "【发送消息】收到首个有效内容片段")
                                                            }

                                                            currentContent.append(regularContent)
                                                            emitter.emitContent(regularContent)
                                                        }
                                                    }
                                                    // 处理message格式（非流式响应）
                                                    else {
                                                        val message =
                                                                choice.optJSONObject("message")
                                                        if (message != null) {
                                                            val reasoningContent =
                                                                    message.optString(
                                                                            "reasoning_content",
                                                                            ""
                                                                    )
                                                            val regularContent =
                                                                    message.optString("content", "")

                                                            // 先处理思考内容（如果有）
                                                            if (reasoningContent.isNotNullOrEmpty()) {
                                                                emitter.emitThinkContent(reasoningContent)
                                                            }

                                                            // 然后处理常规内容
                                                            if (regularContent.isNotNullOrEmpty()) {
                                                                emitter.emitContent(regularContent)
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                // 忽略解析错误，继续处理下一行
                                                Log.w("AIService", "【发送消息】JSON解析错误: ${e.message}")
                                            }
                                        } else {
                                            // 收到流结束标记，如果还在思考模式，确保关闭思考标签
                                            if (isInReasoningMode) {
                                                isInReasoningMode = false
                                                emitter.emitTag("</think>")
                                            }
                                            Log.d("AIService", "【发送消息】收到流结束标记[DONE]")
                                        }
                                    }
                                }
                            }
                            Log.d(
                                    "AIService",
                                    "【发送消息】响应流处理完成，总块数: $chunkCount，输出token: ${tokenCacheManager.outputTokenCount}"
                            )
                        } catch (e: IOException) {
                            // 捕获IO异常，可能是由于取消Call导致的，也可能是网络中断
                            if (isManuallyCancelled) {
                                Log.d("AIService", "【发送消息】流式传输已被用户取消，停止后续操作。")
                                throw UserCancellationException("请求已被用户取消", e)
                            } else {
                                // 这是网络中断的关键点，我们将在这里处理重试逻辑，而不是直接抛出异常
                                Log.e("AIService", "【发送消息】流式读取时发生IO异常，准备重试", e)
                                lastException = e
                                // 跳出useLines和try-catch，进入外层while循环进行重试
                                throw e
                            }
                        }
                        }
                    } else {
                        // 处理非流式响应
                        withContext(Dispatchers.IO) {
                            Log.d("AIService", "【发送消息】开始读取非流式响应")
                            val responseText = responseBody.string()
                            Log.d("AIService", "收到完整响应，长度: ${responseText.length}")
                            
                            val emitter = StreamEmitter(receivedContent, ::emit, onTokensUpdated)
                            
                            try {
                                val jsonResponse = JSONObject(responseText)
                                val choices = jsonResponse.getJSONArray("choices")
                                
                                if (choices.length() > 0) {
                                    val choice = choices.getJSONObject(0)
                                    val message = choice.optJSONObject("message")
                                    
                                    if (message != null) {
                                        // 检查是否有tool_calls（Tool Call API）
                                        val toolCalls = message.optJSONArray("tool_calls")
                                        if (toolCalls != null && toolCalls.length() > 0 && enableToolCall) {
                                            val xmlToolCalls = convertToolCallsToXml(toolCalls)
                                            if (xmlToolCalls.isNotEmpty()) {
                                                emitter.emitContent("\n" + xmlToolCalls)
                                                Log.d("AIService", "Tool Call转XML (非流式): $xmlToolCalls")
                                            }
                                        }
                                        
                                        val reasoningContent = message.optString("reasoning_content", "")
                                        val regularContent = message.optString("content", "")
                                        
                                        // 处理思考内容（如果有）
                                        if (reasoningContent.isNotNullOrEmpty()) {
                                            emitter.emitThinkContent(reasoningContent)
                                        }
                                        
                                        // 处理常规内容
                                        if (regularContent.isNotNullOrEmpty()) {
                                            emitter.emitContent(regularContent)
                                        }
                                    }
                                }
                                
                                // 更新token统计（如果有）
                                val usage = jsonResponse.optJSONObject("usage")
                                if (usage != null) {
                                    val promptTokens = usage.optInt("prompt_tokens", 0)
                                    val completionTokens = usage.optInt("completion_tokens", 0)
                                    if (promptTokens > 0) {
                                        tokenCacheManager.updateActualTokens(promptTokens, 0)
                                        onTokensUpdated(promptTokens, 0, completionTokens)
                                    }
                                }
                                
                                Log.d("AIService", "【发送消息】非流式响应处理完成")
                            } catch (e: Exception) {
                                Log.e("AIService", "【发送消息】解析非流式响应失败", e)
                                throw IOException("解析响应失败: ${e.message}", e)
                            }
                        }
                    }

                    // 清理活跃Call引用
                    activeCall = null
                    Log.d("AIService", "【发送消息】响应处理完成，已清理活跃Call引用")
                } finally {
                    response.close()
                    Log.d("AIService", "【发送消息】关闭响应连接")
                }

                // 成功处理后返回
                Log.d(
                        "AIService",
                        "【发送消息】请求成功完成，输入token: ${tokenCacheManager.totalInputTokenCount}(缓存:${tokenCacheManager.cachedInputTokenCount})，输出token: ${tokenCacheManager.outputTokenCount}"
                )
                return@stream
            } catch (e: NonRetriableException) {
                Log.e("AIService", "【发送消息】发生不可重试错误", e)
                throw e // 直接抛出，不重试
            } catch (e: SocketTimeoutException) {
                lastException = e
                retryCount = handleRetryableError(
                    e, retryCount, maxRetries,
                    "连接超时",
                    "AI响应获取失败，连接超时且已达最大重试次数: ${e.message}",
                    onNonFatalError
                )
            } catch (e: UnknownHostException) {
                lastException = e
                retryCount = handleRetryableError(
                    e, retryCount, maxRetries,
                    "无法解析主机",
                    "无法连接到服务器，请检查网络连接或API地址是否正确",
                    onNonFatalError
                )
            } catch (e: IOException) {
                lastException = e
                retryCount = handleRetryableError(
                    e, retryCount, maxRetries,
                    "网络中断",
                    "AI响应获取失败，已达最大重试次数: ${e.message}",
                    onNonFatalError
                )
            } catch (e: Exception) {
                checkCancellation(e)
                // 其他未知异常，不应重试
                Log.e("AIService", "【发送消息】发生未知异常，停止重试", e)
                throw IOException("AI响应获取失败: ${e.message}", e)
            }
        }

        // 所有重试都失败
        Log.e("AIService", "【发送消息】重试失败，请检查网络连接，最大重试次数: $maxRetries", lastException)
        throw IOException("连接超时或中断，已重试 $maxRetries 次: ${lastException?.message}")
    }
}
