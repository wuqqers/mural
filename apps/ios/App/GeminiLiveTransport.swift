import Foundation
import AVFoundation

@MainActor final class GeminiLiveTransport: NSObject, URLSessionWebSocketDelegate {
    var onEvent: (([String: Any]) -> Void)?
    var onLevels: ((Double, Double) -> Void)?
    var onFailure: ((String) -> Void)?

    private var task: URLSessionWebSocketTask?
    private var session: URLSession?
    private var audioEngine: AVAudioEngine?
    private var outputNode: AVAudioPlayerNode?
    private var isStarted = false
    private(set) var isMuted = false
    private var sessionStartTime: TimeInterval = 0
    private var attempt = UUID()
    private var pendingContinuation: CheckedContinuation<Void, Error>?
    private var receiveTask: Task<Void, Never>?

    func connect(api: APIClient, instructions: String, history: [[String: Any]]) async throws {
        disconnect()
        let token = UUID(); attempt = token
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw TransportError.microphone }
        try Task.checkCancellation()

        let config = api.config
        let apiKey = config.apiKey
        let model = config.resolvedModel

        let urlString = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=\(apiKey)"
        guard let url = URL(string: urlString) else { throw TransportError.connection }

        let urlSession = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        self.session = urlSession
        let wsTask = urlSession.webSocketTask(with: url)
        self.task = wsTask
        wsTask.resume()

        let setupMessage: [String: Any] = [
            "setup": [
                "model": "models/\(model)",
                "responseModalities": ["AUDIO"],
                "speechConfig": [
                    "voiceConfig": [
                        "prebuiltVoiceConfig": ["voiceName": "Kore"]
                    ]
                ],
                "systemInstruction": [
                    "parts": [["text": instructions]]
                ],
                "inputAudioTranscription": [:]
            ]
        ]

        let data = try JSONSerialization.data(withJSONObject: setupMessage)
        guard let jsonString = String(data: data, encoding: .utf8) else { throw TransportError.connection }
        try await wsTask.send(.string(jsonString))

        var receivedSetup = false
        let deadline = Date().addingTimeInterval(15)
        while !receivedSetup && Date() < deadline {
            try Task.checkCancellation()
            guard attempt == token else { throw CancellationError() }
            let message = try await withCheckedThrowingContinuation { (c: CheckedContinuation<URLSessionWebSocketTask.Message, Error>) in
                wsTask.receive { result in c.resume(with: result) }
            }
            if case .string(let text) = message,
               let msgData = text.data(using: .utf8),
               let msg = try? JSONSerialization.jsonObject(with: msgData) as? [String: Any] {
                if msg["setupComplete"] != nil {
                    receivedSetup = true
                    sessionStartTime = Date().timeIntervalSince1970 * 1000
                    onEvent?(["type": "mural.session.created", "session": ["id": "gemini-\(UUID().uuidString)"]])
                    onEvent?(["type": "session.started", "session": ["id": "gemini-\(UUID().uuidString)"]])
                    startAudioStreaming(token: token)
                    startReceiving(token: token)
                }
            }
        }
        guard receivedSetup else { throw TransportError.timeout }
    }

    private func startReceiving(token: UUID) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                guard let wsTask = self.task, self.attempt == token else { return }
                do {
                    let message = try await withCheckedThrowingContinuation { (c: CheckedContinuation<URLSessionWebSocketTask.Message, Error>) in
                        wsTask.receive { result in c.resume(with: result) }
                    }
                    if case .string(let text) = message,
                       let msgData = text.data(using: .utf8),
                       let msg = try? JSONSerialization.jsonObject(with: msgData) as? [String: Any] {
                        await MainActor.run { self.handleServerMessage(msg) }
                    }
                } catch {
                    if !Task.isCancelled {
                        await MainActor.run {
                            self.isStarted = false
                            self.stopAudio()
                            self.onFailure?("Gemini voice connection lost: \(error.localizedDescription)")
                        }
                    }
                    return
                }
            }
        }
    }

    private func handleServerMessage(_ msg: [String: Any]) {
        guard let serverContent = msg["serverContent"] as? [String: Any] else { return }

        if let inputTranscription = serverContent["inputTranscription"] as? [String: Any],
           let text = inputTranscription["text"] as? String, !text.isEmpty {
            let elapsed = Date().timeIntervalSince1970 * 1000 - sessionStartTime
            onEvent?([
                "type": "session.input_transcript.delta",
                "event_id": UUID().uuidString,
                "delta": text,
                "start_ms": max(0, Int(elapsed - 500)),
                "end_ms": Int(elapsed)
            ])
        }

        if let modelTurn = serverContent["modelTurn"] as? [String: Any],
           let parts = modelTurn["parts"] as? [[String: Any]] {
            for part in parts {
                if let inlineData = part["inlineData"] as? [String: Any],
                   let mimeType = inlineData["mimeType"] as? String,
                   let dataStr = inlineData["data"] as? String,
                   mimeType.hasPrefix("audio/"),
                   let audioData = Data(base64Encoded: dataStr) {
                    playAudioData(audioData)
                    continue
                }
                if let text = part["text"] as? String, !text.isEmpty {
                    let elapsed = Date().timeIntervalSince1970 * 1000 - sessionStartTime
                    onEvent?([
                        "type": "session.output_transcript.delta",
                        "event_id": UUID().uuidString,
                        "delta": text,
                        "start_ms": max(0, Int(elapsed - 500)),
                        "end_ms": Int(elapsed)
                    ])
                }
            }
        }

        if let turnComplete = serverContent["turnComplete"] as? Bool, turnComplete {
            let elapsed = Date().timeIntervalSince1970 * 1000 - sessionStartTime
            onEvent?([
                "type": "session.output_transcript.done",
                "event_id": UUID().uuidString,
                "start_ms": max(0, Int(elapsed - 1000)),
                "end_ms": Int(elapsed)
            ])
            onLevels?(0, 0)
        }
    }

    func send(_ event: [String: Any]) -> Bool {
        guard let task, task.state == .running else { return false }
        if let data = try? JSONSerialization.data(withJSONObject: event),
           let json = String(data: data, encoding: .utf8) {
            task.send(.string(json)) { _ in }
            return true
        }
        return false
    }

    func sendText(_ text: String) {
        let msg: [String: Any] = [
            "clientContent": [
                "turns": [["role": "user", "parts": [["text": text]]]],
                "turnComplete": true
            ]
        ]
        _ = send(msg)
    }

    func mute(_ muted: Bool) {
        isMuted = muted
        if muted { onLevels?(0, 0) }
    }

    func close() {
        let closeMsg: [String: Any] = ["clientContent": ["turns": [], "turnComplete": true]]
        _ = send(closeMsg)
        task?.cancel(with: .goingAway, reason: nil)
    }

    func disconnect() {
        attempt = UUID()
        isStarted = false; isMuted = false
        receiveTask?.cancel(); receiveTask = nil
        task?.cancel(with: .goingAway, reason: nil); task = nil
        session?.invalidateAndCancel(); session = nil
        stopAudio()
        onLevels?(0, 0)
    }

    private func startAudioStreaming(token: UUID) {
        let engine = AVAudioEngine()
        let inputNode = engine.inputNode
        let outNode = AVAudioPlayerNode()
        engine.attach(outNode)

        let inputFormat = inputNode.inputFormat(forBus: 0)
        let targetFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true)!
        let outputFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24000, channels: 1, interleaved: true)!
        engine.connect(outNode, to: engine.mainMixerNode, format: outputFormat)

        var converter: AVAudioConverter?
        if inputFormat.sampleRate != 16000 || inputFormat.channelCount != 1 {
            converter = AVAudioConverter(from: inputFormat, to: targetFormat)
        }

        inputNode.installTap(onBus: 0, bufferSize: 3200, format: inputFormat) { [weak self] buffer, _ in
            guard let self, !self.isMuted else { return }
            let sendBuffer: AVAudioPCMBuffer
            if let converter, let converted = converter.convert(buffer) {
                sendBuffer = converted
            } else {
                sendBuffer = buffer
            }
            guard let channelData = sendBuffer.int16ChannelData?[0] else { return }
            let frameLength = Int(sendBuffer.frameLength)
            let pcmData = Data(bytes: channelData, count: frameLength * MemoryLayout<Int16>.size)
            let base64 = pcmData.base64EncodedString()
            let msg: [String: Any] = [
                "realtimeInput": [
                    "audio": ["mimeType": "audio/pcm;rate=16000", "data": base64]
                ]
            ]
            Task { @MainActor [weak self] in self?.send(msg) }
        }

        do {
            try engine.start()
            outNode.play()
            self.audioEngine = engine
            self.outputNode = outNode
            self.isStarted = true
        } catch {
            onFailure?("Failed to start audio: \(error.localizedDescription)")
        }
    }

    private func playAudioData(_ data: Data) {
        guard let outputNode else { return }
        let format = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 24000, channels: 1, interleaved: true)!
        let frameCount = AVAudioFrameCount(data.count / MemoryLayout<Int16>.size)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else { return }
        buffer.frameLength = frameCount
        data.withUnsafeBytes { ptr in
            guard let baseAddress = ptr.baseAddress else { return }
            buffer.int16ChannelData?[0].update(from: baseAddress.assumingMemoryBound(to: Int16.self), count: Int(frameCount))
        }
        outputNode.scheduleBuffer(buffer, completionHandler: nil)
    }

    private func stopAudio() {
        audioEngine?.stop()
        audioEngine = nil
        outputNode = nil
    }

    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                                didOpenWithProtocol protocol: String?) {}
    nonisolated func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                                didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {}
}
