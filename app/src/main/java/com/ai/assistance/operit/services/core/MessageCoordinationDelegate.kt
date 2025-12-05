package com.ai.assistance.operit.services.core

import android.util.Log
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.core.chat.AIMessageManager
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.PromptFunctionType
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.ui.features.chat.viewmodel.UiStateDelegate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * 消息协调委托类
 * 负责消息发送、自动总结、附件清理等核心协调逻辑
 */
class MessageCoordinationDelegate(
    private val coroutineScope: CoroutineScope,
    private val chatHistoryDelegate: ChatHistoryDelegate,
    private val messageProcessingDelegate: MessageProcessingDelegate,
    private val tokenStatsDelegate: TokenStatisticsDelegate,
    private val apiConfigDelegate: ApiConfigDelegate,
    private val attachmentDelegate: AttachmentDelegate,
    private val uiStateDelegate: UiStateDelegate,
    private val getEnhancedAiService: () -> EnhancedAIService?,
    private val updateWebServerForCurrentChat: (String) -> Unit,
    private val resetAttachmentPanelState: () -> Unit,
    private val clearReplyToMessage: () -> Unit,
    private val getReplyToMessage: () -> ChatMessage?
) {
    companion object {
        private const val TAG = "MessageCoordinationDelegate"
    }

    // 总结状态
    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()
    
    // 保存总结任务的 Job 引用，用于取消
    private var summaryJob: Job? = null
    
    // 保存当前的 promptFunctionType，用于自动继续时保持提示词一致性
    private var currentPromptFunctionType: PromptFunctionType = PromptFunctionType.CHAT

    /**
     * 发送用户消息
     * 检查是否有当前对话，如果没有则自动创建新对话
     */
    fun sendUserMessage(promptFunctionType: PromptFunctionType = PromptFunctionType.CHAT) {
        // 检查是否有当前对话，如果没有则创建一个新对话
        if (chatHistoryDelegate.currentChatId.value == null) {
            Log.d(TAG, "当前没有活跃对话，自动创建新对话")

            // 使用 coroutineScope 启动协程
            coroutineScope.launch {
                // 使用现有的createNewChat方法创建新对话
                chatHistoryDelegate.createNewChat()

                // 等待对话ID更新
                var waitCount = 0
                while (chatHistoryDelegate.currentChatId.value == null && waitCount < 10) {
                    delay(100) // 短暂延迟等待对话创建完成
                    waitCount++
                }

                if (chatHistoryDelegate.currentChatId.value == null) {
                    Log.e(TAG, "创建新对话超时，无法发送消息")
                    uiStateDelegate.showErrorMessage("无法创建新对话，请重试")
                    return@launch
                }

                Log.d(
                    TAG,
                    "新对话创建完成，ID: ${chatHistoryDelegate.currentChatId.value}，现在发送消息"
                )

                // 对话创建完成后，发送消息
                sendMessageInternal(promptFunctionType)
            }
        } else {
            // 已有对话，直接发送消息
            sendMessageInternal(promptFunctionType)
        }
    }

    /**
     * 内部发送消息的逻辑
     */
    private fun sendMessageInternal(
        promptFunctionType: PromptFunctionType,
        isContinuation: Boolean = false,
        skipSummaryCheck: Boolean = false,
        isAutoContinuation: Boolean = false
    ) {
        // 如果不是自动续写，更新当前的 promptFunctionType
        if (!isAutoContinuation) {
            currentPromptFunctionType = promptFunctionType
        }
        // 获取当前聊天ID和工作区路径
        val chatId = chatHistoryDelegate.currentChatId.value
        val currentChat = chatHistoryDelegate.chatHistories.value.find { it.id == chatId }
        val workspacePath = currentChat?.workspace

        // 更新本地Web服务器的聊天ID
        chatId?.let { updateWebServerForCurrentChat(it) }

        // 获取当前附件列表
        val currentAttachments = attachmentDelegate.attachments.value

        // 如果不是续写，检查是否需要总结
        if (!isContinuation && !skipSummaryCheck) {
            val currentMessages = chatHistoryDelegate.chatHistory.value
            val currentTokens = tokenStatsDelegate.currentWindowSizeFlow.value
            val maxTokens = (apiConfigDelegate.contextLength.value * 1024).toInt()

            val isShouldGenerateSummary = AIMessageManager.shouldGenerateSummary(
                messages = currentMessages,
                currentTokens = currentTokens,
                maxTokens = maxTokens,
                tokenUsageThreshold = apiConfigDelegate.summaryTokenThreshold.value.toDouble(),
                enableSummary = apiConfigDelegate.enableSummary.value,
                enableSummaryByMessageCount = apiConfigDelegate.enableSummaryByMessageCount.value,
                summaryMessageCountThreshold = apiConfigDelegate.summaryMessageCountThreshold.value
            )

            if (isShouldGenerateSummary) {
                // 主动触发总结，总结后会自动继续
                // 保存当前输入，清空输入框，并立即更新UI状态为总结中
                val userInput = messageProcessingDelegate.userMessage.value.text
                messageProcessingDelegate.updateUserMessage("") // 清空输入框
                // 先设置activeStreamingChatId，确保UI能显示总结状态
                messageProcessingDelegate.setActiveStreamingChatId(chatId)
                messageProcessingDelegate.handleInputProcessingState(InputProcessingState.Summarizing("正在压缩历史记录..."))

                summaryJob = coroutineScope.launch {
                    var summarySuccess = false
                    try {
                        summarySuccess = summarizeHistory(autoContinue = false, promptFunctionType = promptFunctionType)
                        if (summarySuccess) {
                            // 总结成功后，恢复用户输入并继续发送
                            messageProcessingDelegate.updateUserMessage(userInput)
                            sendMessageInternal(
                                promptFunctionType = promptFunctionType,
                                isContinuation = false, // 仍然是用户发起
                                skipSummaryCheck = true // 跳过再次检查
                            )
                        } else {
                            // 总结失败，恢复用户输入并提示
                            messageProcessingDelegate.updateUserMessage(userInput)
                            uiStateDelegate.showErrorMessage("历史记录总结失败，消息未发送")
                        }
                    } catch (e: CancellationException) {
                        // 总结被取消，恢复用户输入但不显示错误消息
                        messageProcessingDelegate.updateUserMessage(userInput)
                        throw e // 重新抛出，让协程正确取消
                    } finally {
                        summaryJob = null
                    }
                }
                return // 阻止当前的消息发送流程
            }
        }


        // 检测是否附着了记忆文件夹
        val hasMemoryFolder = currentAttachments.any {
            it.fileName == "memory_context.xml" && it.mimeType == "application/xml"
        }

        // 如果附着了记忆文件夹，临时启用记忆查询功能
        val shouldEnableMemoryQuery = apiConfigDelegate.enableMemoryQuery.value || hasMemoryFolder

        // 调用messageProcessingDelegate发送消息，并传递附件信息和工作区路径
        messageProcessingDelegate.sendUserMessage(
            attachments = currentAttachments,
            chatId = chatId,
            workspacePath = workspacePath,
            promptFunctionType = promptFunctionType,
            enableThinking = apiConfigDelegate.enableThinkingMode.value,
            thinkingGuidance = apiConfigDelegate.enableThinkingGuidance.value,
            enableMemoryQuery = shouldEnableMemoryQuery,
            enableWorkspaceAttachment = !workspacePath.isNullOrBlank(),
            maxTokens = (apiConfigDelegate.contextLength.value * 1024).toInt(),
            tokenUsageThreshold = apiConfigDelegate.summaryTokenThreshold.value.toDouble(),
            replyToMessage = getReplyToMessage(),
            isAutoContinuation = isAutoContinuation
        )

        // 在sendMessageInternal中，添加对nonFatalErrorEvent的收集
        coroutineScope.launch {
            messageProcessingDelegate.nonFatalErrorEvent.collect { errorMessage ->
                uiStateDelegate.showToast(errorMessage)
            }
        }

        // 只有在非续写（即用户主动发送）时才清空附件和UI状态
        if (!isContinuation) {
            if (currentAttachments.isNotEmpty()) {
                attachmentDelegate.clearAttachments()
            }
            resetAttachmentPanelState()
            clearReplyToMessage()
        }
    }

    /**
     * 手动更新记忆
     */
    fun manuallyUpdateMemory() {
        coroutineScope.launch {
            val enhancedAiService = getEnhancedAiService()
            if (enhancedAiService == null) {
                uiStateDelegate.showToast("AI服务不可用，无法更新记忆")
                return@launch
            }
            if (chatHistoryDelegate.chatHistory.value.isEmpty()) {
                uiStateDelegate.showToast("聊天历史为空，无需更新记忆")
                return@launch
            }

            try {
                // Convert ChatMessage list to List<Pair<String, String>>
                val history = chatHistoryDelegate.chatHistory.value.map { it.sender to it.content }
                // Get the last message content
                val lastMessageContent =
                    chatHistoryDelegate.chatHistory.value.lastOrNull()?.content ?: ""

                enhancedAiService.saveConversationToMemory(
                    history,
                    lastMessageContent
                )
                uiStateDelegate.showToast("记忆已手动更新")
            } catch (e: Exception) {
                Log.e(TAG, "手动更新记忆失败", e)
                uiStateDelegate.showErrorMessage("手动更新记忆失败: ${e.message}")
            }
        }
    }

    /**
     * 手动触发对话总结
     */
    fun manuallySummarizeConversation() {
        if (_isSummarizing.value) {
            uiStateDelegate.showToast("正在总结中，请稍候")
            return
        }
        coroutineScope.launch {
            val success = summarizeHistory(autoContinue = false)
            if (success) {
                uiStateDelegate.showToast("对话总结已生成")
            } else {
                uiStateDelegate.showErrorMessage("总结生成失败，请检查你的功能模型:总结模型")
            }
        }
    }

    /**
     * 处理Token超限的情况，触发一次历史总结并继续。
     */
    fun handleTokenLimitExceeded() {
        Log.d(TAG, "接收到Token超限信号，开始执行总结并继续...")
        summaryJob = coroutineScope.launch {
            summarizeHistory(autoContinue = true)
            summaryJob = null
        }
    }

    /**
     * 取消正在进行的总结操作
     */
    fun cancelSummary() {
        if (_isSummarizing.value) {
            Log.d(TAG, "取消正在进行的总结操作")
            summaryJob?.cancel()
            summaryJob = null
            _isSummarizing.value = false
            // 重置状态
            messageProcessingDelegate.resetLoadingState()
            messageProcessingDelegate.handleInputProcessingState(InputProcessingState.Idle)
        }
    }

    /**
     * 执行历史总结并自动继续对话的核心逻辑
     */
    private suspend fun summarizeHistory(
        autoContinue: Boolean = true,
        promptFunctionType: PromptFunctionType? = null
    ): Boolean {
        if (_isSummarizing.value) {
            Log.d(TAG, "已在总结中，忽略本次请求")
            return false
        }
        _isSummarizing.value = true
        // 先设置activeStreamingChatId，确保UI能显示总结状态
        val currentChatId = chatHistoryDelegate.currentChatId.value
        messageProcessingDelegate.setActiveStreamingChatId(currentChatId)
        messageProcessingDelegate.handleInputProcessingState(InputProcessingState.Summarizing("正在压缩历史记录..."))

        var summarySuccess = false
        try {
            val service = getEnhancedAiService()
            if (service == null) {
                uiStateDelegate.showErrorMessage("AI服务不可用，无法进行总结")
                return false
            }

            val currentMessages = chatHistoryDelegate.chatHistory.value
            if (currentMessages.isEmpty()) {
                Log.d(TAG, "历史记录为空，无需总结")
                return false
            }

            val insertPosition = chatHistoryDelegate.findProperSummaryPosition(currentMessages)
            val summaryMessage = AIMessageManager.summarizeMemory(service, currentMessages, autoContinue)

            if (summaryMessage != null) {
                chatHistoryDelegate.addSummaryMessage(summaryMessage, insertPosition)

                // 更新窗口大小
                val newHistoryForTokens =
                    AIMessageManager.getMemoryFromMessages(chatHistoryDelegate.chatHistory.value)
                val chatService = service.getAIServiceForFunction(FunctionType.CHAT)
                val newWindowSize = chatService.calculateInputTokens("", newHistoryForTokens)
                val (inputTokens, outputTokens) = tokenStatsDelegate.getCumulativeTokenCounts()
                chatHistoryDelegate.saveCurrentChat(inputTokens, outputTokens, newWindowSize)
                withContext(Dispatchers.Main) {
                    tokenStatsDelegate.setTokenCounts(inputTokens, outputTokens, newWindowSize)
                }
                Log.d(TAG, "总结完成，更新窗口大小为: $newWindowSize")
                summarySuccess = true
            } else {
                Log.w(TAG, "总结失败或无需总结")
            }
        } catch (e: CancellationException) {
            // 总结被取消，这是正常流程
            Log.d(TAG, "总结操作被取消")
            throw e // 重新抛出取消异常，让协程正确取消
        } catch (e: Exception) {
            Log.e(TAG, "生成总结时出错: ${e.message}", e)
            uiStateDelegate.showErrorMessage("总结生成失败，请检查你的功能模型:总结模型")
        } finally {
            _isSummarizing.value = false
            val wasSummarizing =
                messageProcessingDelegate.inputProcessingState.value is InputProcessingState.Summarizing

            // 确保加载状态被重置，避免阻塞自动续写
            messageProcessingDelegate.resetLoadingState()

            if (summarySuccess) {
                if (autoContinue) {
                    Log.d(TAG, "总结成功，自动继续对话...")
                    // 使用传入的 promptFunctionType 或当前保存的 promptFunctionType，保持提示词一致性
                    val continuationPromptType = promptFunctionType ?: currentPromptFunctionType
                    sendMessageInternal(
                        promptFunctionType = continuationPromptType,
                        isContinuation = true,
                        isAutoContinuation = true
                    )
                }
            } else if (wasSummarizing) {
                // 总结未成功时才恢复到Idle，避免覆盖后续续写状态
                messageProcessingDelegate.handleInputProcessingState(InputProcessingState.Idle)
            }
        }
        return summarySuccess
    }
}