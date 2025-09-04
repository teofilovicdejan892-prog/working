/**
 * P8FS SSE Chat Parsing Flow - Android Implementation
 * 
 * Based on reference implementation from T1 app
 * Key features:
 * 1. Session ID management for conversation threads
 * 2. Comprehensive header management from content_headers.md
 * 3. SSE streaming with real-time chunk parsing
 * 4. Only send latest message, server manages thread context
 * 5. Support for audio chat via X-CHAT-IS-AUDIO header
 */

import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import java.util.*

data class ChatMessage(
    val role: MessageRole,
    val content: String
)

enum class MessageRole {
    USER, ASSISTANT
}

data class ChatCompletionRequest(
    val model: String = "gpt-4.1-mini",
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val maxTokens: Int = 1000,
    val stream: Boolean = true
)

data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val choices: List<StreamChoice>
)

data class StreamChoice(
    val index: Int = 0,
    val delta: Delta,
    val finishReason: String? = null
)

data class Delta(
    val content: String? = null
)

enum class P8Agent(val key: String) {
    P8_SIMULATION("p8-sim"),
    P8_RESEARCH("p8-research"),
    P8_ANALYSIS("p8-analysis"),
    P8_QA("p8-qa")
}

sealed class ChatStreamEvent {
    data class Content(val text: String) : ChatStreamEvent()
    data class Error(val exception: Exception) : ChatStreamEvent()
    object Done : ChatStreamEvent()
}

/**
 * Main chat client with session management and comprehensive headers
 */
class P8FSChatClient(
    private val headersManager: HeadersManager
) {
    companion object {
        private const val TAG = "P8FSChatClient"
    }
    
    // Generate unique session ID for this chat conversation
    private val sessionId = UUID.randomUUID().toString().also { newSessionId ->
        Log.d(TAG, "🆔 Generated new chat session ID: $newSessionId")
    }
    
    private val httpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60000 // 60 seconds for streaming
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 60000
        }
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Stream chat completion with comprehensive P8FS headers
     * Only sends the latest user message - server manages thread via session ID
     */
    fun streamChatCompletion(
        userMessage: String,
        agent: P8Agent? = P8Agent.P8_SIMULATION,
        model: String = "gpt-4.1-mini"
    ): Flow<ChatStreamEvent> = flow {
        try {
            // Create request with only the new user message
            val request = ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage(MessageRole.USER, userMessage)),
                stream = true
            )
            
            // Determine endpoint based on agent
            val endpoint = when (agent) {
                null -> "${AppConfig.baseUrl}/api/v1/chat/completions"
                else -> "${AppConfig.baseUrl}/api/v1/agent/${agent.key}/chat/completions"
            }
            
            Log.d(TAG, "🚀 Starting streaming chat to: $endpoint")
            Log.d(TAG, "📝 User message: $userMessage")
            Log.d(TAG, "🤖 Agent: ${agent?.key ?: "none"}")
            
            // Get comprehensive headers including session ID
            val chatHeaders = headersManager.getChatHeaders(sessionId)
            Log.d(TAG, "🆔 Session ID: $sessionId")
            Log.d(TAG, "📋 Headers: ${chatHeaders.keys.joinToString(", ")}")
            
            httpClient.preparePost(endpoint) {
                // Apply all P8FS content headers
                chatHeaders.forEach { (key, value) ->
                    header(key, value)
                }
                contentType(ContentType.Application.Json)
                setBody(request)
            }.execute { response ->
                
                if (response.status != HttpStatusCode.OK) {
                    val errorText = response.bodyAsText()
                    Log.e(TAG, "❌ Streaming failed: ${response.status} - $errorText")
                    emit(ChatStreamEvent.Error(Exception("Chat failed: ${response.status}")))
                    return@execute
                }
                
                Log.d(TAG, "✅ SSE stream connected")
                
                // Parse SSE stream
                val channel = response.bodyAsChannel()
                val contentBuffer = StringBuilder()
                var doneEmitted = false
                
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    
                    // SSE format: "data: {json}" or "data: [DONE]"
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        
                        when (data) {
                            "[DONE]" -> {
                                Log.d(TAG, "🏁 Stream completed with [DONE] signal")
                                emit(ChatStreamEvent.Done)
                                doneEmitted = true
                                break
                            }
                            else -> {
                                try {
                                    val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                                    
                                    // Extract content from delta
                                    chunk.choices.firstOrNull()?.delta?.content?.let { content ->
                                        Log.d(TAG, "📝 Received chunk: '$content'")
                                        emit(ChatStreamEvent.Content(content))
                                        contentBuffer.append(content)
                                    }
                                    
                                    // Check for completion
                                    chunk.choices.firstOrNull()?.finishReason?.let { reason ->
                                        Log.d(TAG, "🏁 Finish reason: $reason")
                                    }
                                    
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error parsing chunk: $data", e)
                                }
                            }
                        }
                    }
                }
                
                Log.d(TAG, "📊 Stream ended. Total content: ${contentBuffer.length} chars")
                
                // Ensure Done is emitted even if server doesn't send [DONE]
                if (!doneEmitted) {
                    Log.d(TAG, "🏁 Emitting Done (stream ended without [DONE])")
                    emit(ChatStreamEvent.Done)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Streaming error", e)
            emit(ChatStreamEvent.Error(e))
        }
    }
    
    /**
     * Stream chat with audio input
     * Sets X-CHAT-IS-AUDIO header for server-side transcription
     */
    fun streamChatCompletionWithAudio(
        audioContent: String, // Base64 encoded WAV audio
        agent: P8Agent? = P8Agent.P8_SIMULATION,
        model: String = "gpt-4.1-mini"
    ): Flow<ChatStreamEvent> = flow {
        try {
            // Create request with audio content as user message
            val request = ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage(MessageRole.USER, audioContent)),
                stream = true
            )
            
            val endpoint = when (agent) {
                null -> "${AppConfig.baseUrl}/api/v1/chat/completions"
                else -> "${AppConfig.baseUrl}/api/v1/agent/${agent.key}/chat/completions"
            }
            
            Log.d(TAG, "🎤 Starting audio chat stream to: $endpoint")
            
            val chatHeaders = headersManager.getChatHeaders(sessionId)
            Log.d(TAG, "🆔 Audio session ID: $sessionId")
            
            httpClient.preparePost(endpoint) {
                // Apply all standard headers
                chatHeaders.forEach { (key, value) ->
                    header(key, value)
                }
                
                // Add audio transcription header
                header("X-CHAT-IS-AUDIO", "true")
                
                contentType(ContentType.Application.Json)
                setBody(request)
            }.execute { response ->
                
                if (response.status != HttpStatusCode.OK) {
                    val errorText = response.bodyAsText()
                    Log.e(TAG, "❌ Audio chat failed: ${response.status} - $errorText")
                    emit(ChatStreamEvent.Error(Exception("Audio chat failed: ${response.status}")))
                    return@execute
                }
                
                Log.d(TAG, "🎤✅ Audio SSE stream connected")
                
                // Parse SSE stream (same logic as text chat)
                val channel = response.bodyAsChannel()
                val contentBuffer = StringBuilder()
                var doneEmitted = false
                
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    
                    if (line.startsWith("data: ")) {
                        val data = line.substring(6).trim()
                        
                        if (data == "[DONE]") {
                            Log.d(TAG, "🎤🏁 Audio stream completed")
                            emit(ChatStreamEvent.Done)
                            doneEmitted = true
                            break
                        }
                        
                        try {
                            val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                            chunk.choices.firstOrNull()?.delta?.content?.let { content ->
                                emit(ChatStreamEvent.Content(content))
                                contentBuffer.append(content)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "❌ Failed to parse audio stream chunk: $data", e)
                        }
                    }
                }
                
                if (!doneEmitted) {
                    emit(ChatStreamEvent.Done)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Audio streaming error", e)
            emit(ChatStreamEvent.Error(e))
        }
    }
    
    /**
     * Start a new chat conversation (new session ID)
     */
    fun startNewConversation(): String {
        val newSessionId = UUID.randomUUID().toString()
        Log.d(TAG, "🆕 Starting new conversation with session ID: $newSessionId")
        return newSessionId
    }
    
    fun close() {
        httpClient.close()
    }
}

/**
 * Headers manager for P8FS content headers
 * Implements all headers from content_headers.md
 */
class HeadersManager {
    
    fun getChatHeaders(sessionId: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        
        // Authentication
        headers["Authorization"] = "Bearer ${getStoredAccessToken()}"
        
        // Session Management
        headers["X-Session-ID"] = sessionId
        headers["X-Tenant-ID"] = getStoredTenantId()
        
        // User Context
        headers["X-User-Email"] = getStoredUserEmail()
        headers["X-User-Source-ID"] = getStoredUserId()
        
        // Device Context
        headers["X-Device-ID"] = getDeviceId()
        headers["X-Device-Type"] = "mobile"
        headers["X-Device-Platform"] = "Android"
        headers["X-Device-Model"] = android.os.Build.MODEL
        headers["X-Device-Version"] = android.os.Build.VERSION.RELEASE
        headers["X-App-Version"] = getAppVersion()
        headers["X-OS-Version"] = android.os.Build.VERSION.RELEASE
        headers["X-Platform"] = "Android"
        
        // Mobile-specific
        headers["X-Biometric-Available"] = isBiometricAvailable().toString()
        headers["X-Secure-Enclave"] = hasSecureEnclave().toString()
        
        // Content Context for Chat
        headers["X-Content-Type"] = "application/json"
        headers["X-Content-Language"] = getDeviceLanguage()
        headers["X-Processing-Context"] = "chat-completion"
        
        Log.d("HeadersManager", "📋 Generated ${headers.size} chat headers")
        
        return headers
    }
    
    fun generateNewSessionId(): String = UUID.randomUUID().toString()
    
    // Implementation-specific methods
    private fun getStoredAccessToken(): String = TODO("Retrieve from secure storage")
    private fun getStoredTenantId(): String = TODO("Retrieve from secure storage") 
    private fun getStoredUserEmail(): String = TODO("Retrieve from secure storage")
    private fun getStoredUserId(): String = TODO("Retrieve from secure storage")
    private fun getDeviceId(): String = TODO("Get unique device identifier")
    private fun getAppVersion(): String = TODO("Get from BuildConfig")
    private fun isBiometricAvailable(): Boolean = TODO("Check biometric capability")
    private fun hasSecureEnclave(): Boolean = TODO("Check for secure hardware")
    private fun getDeviceLanguage(): String = TODO("Get device locale")
}

// Usage Example:
/*
class ChatViewModel : ViewModel() {
    
    private val headersManager = HeadersManager()
    private val chatClient = P8FSChatClient(headersManager)
    
    fun sendMessage(userMessage: String) {
        viewModelScope.launch {
            chatClient.streamChatCompletion(
                userMessage = userMessage,
                agent = P8Agent.P8_SIMULATION
            ).collect { event ->
                when (event) {
                    is ChatStreamEvent.Content -> {
                        // Update UI with streaming content
                        updateStreamingMessage(event.text)
                    }
                    is ChatStreamEvent.Done -> {
                        // Finalize message display
                        completeMessage()
                    }
                    is ChatStreamEvent.Error -> {
                        // Handle error
                        showError(event.exception.message ?: "Chat error")
                    }
                }
            }
        }
    }
    
    fun sendAudioMessage(audioData: String) {
        viewModelScope.launch {
            chatClient.streamChatCompletionWithAudio(
                audioContent = audioData,
                agent = P8Agent.P8_SIMULATION
            ).collect { event ->
                // Handle audio chat events same as text
            }
        }
    }
}
*/