/**
 * P8FS SSE Chat Parsing Flow - iOS Implementation
 *
 * Based on reference Android implementation from T1 app
 * Key features:
 * 1. Session ID management for conversation threads
 * 2. Comprehensive header management from content_headers.md
 * 3. SSE streaming with URLSessionDataTask
 * 4. Only send latest message, server manages thread context
 * 5. Support for audio chat via X-CHAT-IS-AUDIO header
 */

import Foundation
import Combine
import os.log

// MARK: - Data Models

struct ChatMessage: Codable {
    let role: MessageRole
    let content: String
}

enum MessageRole: String, Codable {
    case user = "user"
    case assistant = "assistant"
}

struct ChatCompletionRequest: Codable {
    let model: String
    let messages: [ChatMessage]
    let temperature: Double
    let maxTokens: Int
    let stream: Bool
    
    enum CodingKeys: String, CodingKey {
        case model, messages, temperature, stream
        case maxTokens = "max_tokens"
    }
}

struct ChatCompletionChunk: Codable {
    let id: String
    let object: String
    let choices: [StreamChoice]
}

struct StreamChoice: Codable {
    let index: Int
    let delta: Delta
    let finishReason: String?
    
    enum CodingKeys: String, CodingKey {
        case index, delta
        case finishReason = "finish_reason"
    }
}

struct Delta: Codable {
    let content: String?
}

enum P8Agent: String, CaseIterable {
    case p8Simulation = "p8-sim"
    case p8Research = "p8-research" 
    case p8Analysis = "p8-analysis"
    case p8QA = "p8-qa"
}

enum ChatStreamEvent {
    case content(String)
    case done
    case error(Error)
}

// MARK: - Main Chat Client

@MainActor
class P8FSChatClient: ObservableObject {
    
    private let logger = Logger(subsystem: "app.p8fs", category: "ChatClient")
    private let headersManager: HeadersManager
    
    // Generate unique session ID for this chat conversation
    private let sessionId = UUID().uuidString
    
    private var urlSession: URLSession
    private var streamTask: URLSessionDataTask?
    
    init(headersManager: HeadersManager) {
        self.headersManager = headersManager
        
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60.0 // 60 seconds for streaming
        config.timeoutIntervalForResource = 60.0
        
        self.urlSession = URLSession(configuration: config)
        
        logger.info("🆔 Generated new chat session ID: \(self.sessionId)")
    }
    
    /**
     * Stream chat completion with comprehensive P8FS headers
     * Only sends the latest user message - server manages thread via session ID
     */
    func streamChatCompletion(
        userMessage: String,
        agent: P8Agent? = .p8Simulation,
        model: String = "gpt-4.1-mini"
    ) -> AsyncThrowingStream<ChatStreamEvent, Error> {
        
        return AsyncThrowingStream { continuation in
            Task {
                do {
                    // Create request with only the new user message
                    let request = ChatCompletionRequest(
                        model: model,
                        messages: [ChatMessage(role: .user, content: userMessage)],
                        temperature: 0.7,
                        maxTokens: 1000,
                        stream: true
                    )
                    
                    // Determine endpoint based on agent
                    let baseURL = AppConfig.baseUrl
                    let endpoint: String
                    if let agent = agent {
                        endpoint = "\(baseURL)/api/v1/agent/\(agent.rawValue)/chat/completions"
                    } else {
                        endpoint = "\(baseURL)/api/v1/chat/completions"
                    }
                    
                    logger.info("🚀 Starting streaming chat to: \(endpoint)")
                    logger.info("📝 User message: \(userMessage)")
                    logger.info("🤖 Agent: \(agent?.rawValue ?? "none")")
                    
                    // Create URL request
                    guard let url = URL(string: endpoint) else {
                        throw ChatError.invalidURL
                    }
                    
                    var urlRequest = URLRequest(url: url)
                    urlRequest.httpMethod = "POST"
                    urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    
                    // Apply comprehensive P8FS headers
                    let chatHeaders = headersManager.getChatHeaders(sessionId: sessionId)
                    for (key, value) in chatHeaders {
                        urlRequest.setValue(value, forHTTPHeaderField: key)
                    }
                    
                    logger.info("🆔 Session ID: \(self.sessionId)")
                    logger.info("📋 Headers: \(chatHeaders.keys.joined(separator: ", "))")
                    
                    // Set request body
                    let jsonData = try JSONEncoder().encode(request)
                    urlRequest.httpBody = jsonData
                    
                    // Start streaming task
                    streamTask = urlSession.dataTask(with: urlRequest) { [weak self] data, response, error in
                        
                        if let error = error {
                            self?.logger.error("❌ Streaming error: \(error.localizedDescription)")
                            continuation.finish(throwing: error)
                            return
                        }
                        
                        guard let httpResponse = response as? HTTPURLResponse else {
                            continuation.finish(throwing: ChatError.invalidResponse)
                            return
                        }
                        
                        guard httpResponse.statusCode == 200 else {
                            self?.logger.error("❌ HTTP error: \(httpResponse.statusCode)")
                            continuation.finish(throwing: ChatError.httpError(httpResponse.statusCode))
                            return
                        }
                        
                        guard let data = data else { return }
                        
                        // Parse SSE stream
                        let dataString = String(data: data, encoding: .utf8) ?? ""
                        self?.parseSSEData(dataString, continuation: continuation)
                    }
                    
                    streamTask?.resume()
                    
                } catch {
                    logger.error("❌ Failed to start stream: \(error.localizedDescription)")
                    continuation.finish(throwing: error)
                }
            }
        }
    }
    
    /**
     * Stream chat with audio input
     * Sets X-CHAT-IS-AUDIO header for server-side transcription
     */
    func streamChatCompletionWithAudio(
        audioContent: String, // Base64 encoded WAV audio
        agent: P8Agent? = .p8Simulation,
        model: String = "gpt-4.1-mini"
    ) -> AsyncThrowingStream<ChatStreamEvent, Error> {
        
        return AsyncThrowingStream { continuation in
            Task {
                do {
                    let request = ChatCompletionRequest(
                        model: model,
                        messages: [ChatMessage(role: .user, content: audioContent)],
                        temperature: 0.7,
                        maxTokens: 1000,
                        stream: true
                    )
                    
                    let baseURL = AppConfig.baseUrl
                    let endpoint: String
                    if let agent = agent {
                        endpoint = "\(baseURL)/api/v1/agent/\(agent.rawValue)/chat/completions"
                    } else {
                        endpoint = "\(baseURL)/api/v1/chat/completions"
                    }
                    
                    logger.info("🎤 Starting audio chat stream to: \(endpoint)")
                    
                    guard let url = URL(string: endpoint) else {
                        throw ChatError.invalidURL
                    }
                    
                    var urlRequest = URLRequest(url: url)
                    urlRequest.httpMethod = "POST"
                    urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    
                    // Apply standard headers
                    let chatHeaders = headersManager.getChatHeaders(sessionId: sessionId)
                    for (key, value) in chatHeaders {
                        urlRequest.setValue(value, forHTTPHeaderField: key)
                    }
                    
                    // Add audio transcription header
                    urlRequest.setValue("true", forHTTPHeaderField: "X-CHAT-IS-AUDIO")
                    
                    logger.info("🆔 Audio session ID: \(self.sessionId)")
                    
                    let jsonData = try JSONEncoder().encode(request)
                    urlRequest.httpBody = jsonData
                    
                    streamTask = urlSession.dataTask(with: urlRequest) { [weak self] data, response, error in
                        
                        if let error = error {
                            self?.logger.error("❌ Audio streaming error: \(error.localizedDescription)")
                            continuation.finish(throwing: error)
                            return
                        }
                        
                        guard let httpResponse = response as? HTTPURLResponse,
                              httpResponse.statusCode == 200 else {
                            continuation.finish(throwing: ChatError.httpError(500))
                            return
                        }
                        
                        guard let data = data else { return }
                        
                        let dataString = String(data: data, encoding: .utf8) ?? ""
                        self?.parseSSEData(dataString, continuation: continuation)
                    }
                    
                    streamTask?.resume()
                    
                } catch {
                    logger.error("❌ Failed to start audio stream: \(error.localizedDescription)")
                    continuation.finish(throwing: error)
                }
            }
        }
    }
    
    /**
     * Parse Server-Sent Events data
     */
    private func parseSSEData(
        _ data: String, 
        continuation: AsyncThrowingStream<ChatStreamEvent, Error>.Continuation
    ) {
        let lines = data.components(separatedBy: .newlines)
        
        for line in lines {
            if line.hasPrefix("data: ") {
                let sseData = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
                
                if sseData == "[DONE]" {
                    logger.info("🏁 Stream completed with [DONE] signal")
                    continuation.yield(.done)
                    continuation.finish()
                    return
                }
                
                // Parse JSON chunk
                if let jsonData = sseData.data(using: .utf8) {
                    do {
                        let chunk = try JSONDecoder().decode(ChatCompletionChunk.self, from: jsonData)
                        
                        if let content = chunk.choices.first?.delta.content {
                            logger.debug("📝 Received chunk: '\(content)'")
                            continuation.yield(.content(content))
                        }
                        
                        if let finishReason = chunk.choices.first?.finishReason {
                            logger.info("🏁 Finish reason: \(finishReason)")
                        }
                        
                    } catch {
                        logger.error("❌ Error parsing chunk: \(sseData) - \(error.localizedDescription)")
                    }
                }
            }
        }
    }
    
    /**
     * Start a new chat conversation (new session ID)
     */
    func startNewConversation() -> String {
        let newSessionId = UUID().uuidString
        logger.info("🆕 Starting new conversation with session ID: \(newSessionId)")
        return newSessionId
    }
    
    /**
     * Cancel current streaming task
     */
    func cancelStream() {
        streamTask?.cancel()
        streamTask = nil
    }
}

// MARK: - Headers Manager

class HeadersManager {
    
    private let logger = Logger(subsystem: "app.p8fs", category: "HeadersManager")
    
    func getChatHeaders(sessionId: String) -> [String: String] {
        var headers: [String: String] = [:]
        
        // Authentication
        headers["Authorization"]  = "Bearer \(getStoredAccessToken())"
        
        // Session Management
        headers["X-Session-ID"] = sessionId
        headers["X-Tenant-ID"] = getStoredTenantId()
        
        // User Context
        headers["X-User-Email"] = getStoredUserEmail()
        headers["X-User-Source-ID"] = getStoredUserId()
        
        // Device Context
        headers["X-Device-ID"] = getDeviceId()
        headers["X-Device-Type"] = "mobile"
        headers["X-Device-Platform"] = "iOS"
        headers["X-Device-Model"] = UIDevice.current.model
        headers["X-Device-Version"] = UIDevice.current.systemVersion
        headers["X-App-Version"] = getAppVersion()
        headers["X-OS-Version"] = UIDevice.current.systemVersion
        headers["X-Platform"] = "iOS"
        
        // iOS-specific
        headers["X-Biometric-Available"] = String(isBiometricAvailable())
        headers["X-Secure-Enclave"] = String(hasSecureEnclave())
        
        // Content Context for Chat
        headers["X-Content-Type"] = "application/json"
        headers["X-Content-Language"] = getDeviceLanguage()
        headers["X-Processing-Context"] = "chat-completion"
        
        logger.info("📋 Generated \(headers.count) chat headers")
        
        return headers
    }
    
    func generateNewSessionId() -> String {
        return UUID().uuidString
    }
    
    // Implementation-specific methods
    private func getStoredAccessToken() -> String {
        // TODO: Retrieve from Keychain
        return ""
    }
    
    private func getStoredTenantId() -> String {
        // TODO: Retrieve from secure storage
        return ""
    }
    
    private func getStoredUserEmail() -> String {
        // TODO: Retrieve from secure storage
        return ""
    }
    
    private func getStoredUserId() -> String {
        // TODO: Retrieve from secure storage
        return ""
    }
    
    private func getDeviceId() -> String {
        // TODO: Get unique device identifier
        return UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
    }
    
    private func getAppVersion() -> String {
        return Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    }
    
    private func isBiometricAvailable() -> Bool {
        // TODO: Check biometric capability with LocalAuthentication
        return false
    }
    
    private func hasSecureEnclave() -> Bool {
        // TODO: Check for Secure Enclave availability
        return false
    }
    
    private func getDeviceLanguage() -> String {
        return Locale.preferredLanguages.first ?? "en-US"
    }
}

// MARK: - Error Types

enum ChatError: LocalizedError {
    case invalidURL
    case invalidResponse
    case httpError(Int)
    case parsingError
    
    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Invalid URL"
        case .invalidResponse:
            return "Invalid response"
        case .httpError(let code):
            return "HTTP error: \(code)"
        case .parsingError:
            return "Parsing error"
        }
    }
}

// MARK: - App Config

struct AppConfig {
    static let baseUrl = "https://p8fs.percolationlabs.ai"
}

// Usage Example:
/*
@MainActor
class ChatViewModel: ObservableObject {
    
    @Published var messages: [ChatMessage] = []
    @Published var isStreaming = false
    @Published var currentStreamingContent = ""
    
    private let headersManager = HeadersManager()
    private let chatClient: P8FSChatClient
    
    init() {
        self.chatClient = P8FSChatClient(headersManager: headersManager)
    }
    
    func sendMessage(_ userMessage: String) {
        // Add user message immediately
        messages.append(ChatMessage(role: .user, content: userMessage))
        
        // Start streaming assistant response
        isStreaming = true
        currentStreamingContent = ""
        
        Task {
            do {
                for try await event in chatClient.streamChatCompletion(
                    userMessage: userMessage,
                    agent: .p8Simulation
                ) {
                    await MainActor.run {
                        switch event {
                        case .content(let text):
                            currentStreamingContent += text
                        case .done:
                            messages.append(ChatMessage(role: .assistant, content: currentStreamingContent))
                            isStreaming = false
                            currentStreamingContent = ""
                        case .error(let error):
                            print("Chat error: \(error.localizedDescription)")
                            isStreaming = false
                        }
                    }
                }
            } catch {
                await MainActor.run {
                    print("Stream error: \(error.localizedDescription)")
                    isStreaming = false
                }
            }
        }
    }
    
    func sendAudioMessage(_ audioData: String) {
        Task {
            for try await event in chatClient.streamChatCompletionWithAudio(
                audioContent: audioData,
                agent: .p8Simulation
            ) {
                // Handle audio chat events same as text
                await MainActor.run {
                    switch event {
                    case .content(let text):
                        currentStreamingContent += text
                    case .done:
                        messages.append(ChatMessage(role: .assistant, content: currentStreamingContent))
                        isStreaming = false
                        currentStreamingContent = ""
                    case .error(let error):
                        print("Audio chat error: \(error.localizedDescription)")
                        isStreaming = false
                    }
                }
            }
        }
    }
}
*/