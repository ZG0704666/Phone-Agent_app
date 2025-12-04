package com.ai.assistance.operit.api.chat.llmprovider

import android.util.Log
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.Stream
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 针对DeepSeek模型的特定API Provider。
 * 继承自OpenAIProvider，以重用大部分兼容逻辑，但特别处理了`reasoning_content`参数。
 * 当启用推理模式时，会将assistant消息中的<think>标签内容提取出来作为reasoning_content字段。
 */
class DeepseekProvider(
    apiEndpoint: String,
    apiKeyProvider: ApiKeyProvider,
    modelName: String,
    client: OkHttpClient,
    customHeaders: Map<String, String> = emptyMap(),
    providerType: com.ai.assistance.operit.data.model.ApiProviderType = com.ai.assistance.operit.data.model.ApiProviderType.DEEPSEEK,
    supportsVision: Boolean = false,
    enableToolCall: Boolean = false,
    private val enableReasoning: Boolean = false // 是否启用推理模式
) : OpenAIProvider(apiEndpoint, apiKeyProvider, modelName, client, customHeaders, providerType, supportsVision, enableToolCall) {

    /**
     * 重写创建请求体的方法，以支持DeepSeek的`reasoning_content`参数。
     * 当启用推理模式时，需要特殊处理消息格式。
     */
    override fun createRequestBody(
        message: String,
        chatHistory: List<Pair<String, String>>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?
    ): RequestBody {
        // 如果未启用推理模式，直接使用父类的实现
        if (!enableReasoning) {
            return super.createRequestBody(message, chatHistory, modelParameters, enableThinking, stream, availableTools)
        }

        // 启用推理模式时，需要特殊处理
        val jsonObject = JSONObject()
        jsonObject.put("model", modelName)
        jsonObject.put("stream", stream)

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
                            Log.w("DeepseekProvider", "OBJECT参数解析失败: ${param.apiName}", e)
                            null
                        }
                        if (parsed != null) {
                            jsonObject.put(param.apiName, parsed)
                        } else {
                            jsonObject.put(param.apiName, raw)
                        }
                    }
                }
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
                jsonObject.put("tool_choice", "auto")
                toolsJson = tools.toString()
                Log.d("DeepseekProvider", "Tool Call已启用，添加了 ${tools.length()} 个工具定义")
            }
        }

        // 使用特殊的消息构建方法（支持reasoning_content）
        val messagesArray = buildMessagesWithReasoning(message, chatHistory, effectiveEnableToolCall)
        jsonObject.put("messages", messagesArray)

        // 记录最终的请求体（省略过长的tools字段）
        val logJson = JSONObject(jsonObject.toString())
        if (logJson.has("tools")) {
            val toolsArray = logJson.getJSONArray("tools")
            logJson.put("tools", "[${toolsArray.length()} tools omitted for brevity]")
        }
        logLargeString("DeepseekProvider", logJson.toString(4), "最终DeepSeek推理模式请求体: ")

        return jsonObject.toString().toRequestBody(JSON)
    }

    /**
     * 构建支持reasoning_content的消息数组
     * 对于assistant角色的消息，提取<think>标签内容作为reasoning_content
     */
    private fun buildMessagesWithReasoning(
        message: String,
        chatHistory: List<Pair<String, String>>,
        useToolCall: Boolean
    ): JSONArray {
        val messagesArray = JSONArray()

        // 检查当前消息是否已经在历史记录的末尾（避免重复）
        val isMessageInHistory = chatHistory.isNotEmpty() && chatHistory.last().second == message

        // 如果消息已在历史中，只处理历史；否则需要处理历史+当前消息
        val effectiveHistory = if (isMessageInHistory) {
            chatHistory
        } else {
            chatHistory + ("user" to message)
        }

        // 追踪上一个assistant消息中的tool_call_ids
        val lastToolCallIds = mutableListOf<String>()

        if (effectiveHistory.isNotEmpty()) {
            // 使用统一的角色映射，保留think标签内容
            val standardizedHistory = ChatUtils.mapChatHistoryToStandardRoles(effectiveHistory, extractThinking = true)
            val mergedHistory = mutableListOf<Pair<String, String>>()

            for ((role, content) in standardizedHistory) {
                if (mergedHistory.isNotEmpty() &&
                    role == mergedHistory.last().first &&
                    role != "system"
                ) {
                    val lastMessage = mergedHistory.last()
                    mergedHistory[mergedHistory.size - 1] =
                        Pair(lastMessage.first, lastMessage.second + "\n" + content)
                    Log.d("DeepseekProvider", "合并连续的 $role 消息")
                } else {
                    mergedHistory.add(Pair(role, content))
                }
            }

            for ((role, originalContent) in mergedHistory) {
                // 对于assistant消息，提取reasoning_content
                if (role == "assistant") {
                    // 提取think标签内容
                    val (content, reasoningContent) = ChatUtils.extractThinkingContent(originalContent)

                    if (useToolCall) {
                        // 启用Tool Call时，解析XML tool calls
                        val (textContent, toolCalls) = parseXmlToolCalls(content)
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)

                        // DeepSeek推理模式要求所有assistant消息都必须有reasoning_content字段
                        historyMessage.put("reasoning_content", reasoningContent)
                        if (reasoningContent.isNotEmpty()) {
                            Log.d("DeepseekProvider", "添加reasoning_content，长度: ${reasoningContent.length}")
                        } else {
                            Log.d("DeepseekProvider", "添加空reasoning_content")
                        }

                        val effectiveContent = if (content.isBlank()) {
                            Log.d("DeepseekProvider", "发现空的assistant消息，填充为[Empty]")
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
                            // 记录tool_call_ids
                            lastToolCallIds.clear()
                            for (i in 0 until toolCalls.length()) {
                                lastToolCallIds.add(toolCalls.getJSONObject(i).getString("id"))
                            }
                        }

                        messagesArray.put(historyMessage)
                    } else {
                        // 不使用Tool Call时，简单处理
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)

                        // DeepSeek推理模式要求所有assistant消息都必须有reasoning_content字段
                        historyMessage.put("reasoning_content", reasoningContent)
                        if (reasoningContent.isNotEmpty()) {
                            Log.d("DeepseekProvider", "添加reasoning_content，长度: ${reasoningContent.length}")
                        } else {
                            Log.d("DeepseekProvider", "添加空reasoning_content")
                        }

                        historyMessage.put("content", buildContentField(content.ifBlank { "[Empty]" }))
                        messagesArray.put(historyMessage)
                    }
                } else {
                    // 非assistant消息，使用原有逻辑
                    if (useToolCall && role == "user") {
                        val (textContent, toolResults) = parseXmlToolResults(originalContent)
                        if (toolResults != null && toolResults.isNotEmpty()) {
                            // 添加tool消息
                            toolResults.forEachIndexed { index, (_, resultContent) ->
                                val toolMessage = JSONObject()
                                toolMessage.put("role", "tool")
                                val toolCallId = lastToolCallIds.getOrNull(index) ?: "call_result_$index"
                                toolMessage.put("tool_call_id", toolCallId)
                                toolMessage.put("content", resultContent)
                                messagesArray.put(toolMessage)
                                Log.d("DeepseekProvider", "历史XML→ToolResult: ID=$toolCallId, content length=${resultContent.length}")
                            }

                            if (textContent.isNotEmpty()) {
                                val userMessage = JSONObject()
                                userMessage.put("role", "user")
                                userMessage.put("content", buildContentField(textContent))
                                messagesArray.put(userMessage)
                                Log.d("DeepseekProvider", "历史user消息有剩余文本: length=${textContent.length}, preview=${textContent.take(10)}")
                            } else {
                                Log.d("DeepseekProvider", "历史user消息全是tool_result，无剩余文本")
                            }
                        } else {
                            val historyMessage = JSONObject()
                            historyMessage.put("role", role)
                            historyMessage.put("content", buildContentField(originalContent))
                            messagesArray.put(historyMessage)
                        }
                    } else {
                        val historyMessage = JSONObject()
                        historyMessage.put("role", role)
                        historyMessage.put("content", buildContentField(originalContent))
                        messagesArray.put(historyMessage)
                    }
                }
            }
        }

        return messagesArray
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
    ): Stream<String> {
        // 直接调用父类的sendMessage实现
        return super.sendMessage(message, chatHistory, modelParameters, enableThinking, stream, availableTools, onTokensUpdated, onNonFatalError)
    }
}
