import Foundation
import MuralCore

final class NoRedirect: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

struct APIUsage { var input = 0; var output = 0; var searches = 0 }
struct APIResult { var text: String; var sources: [SourceLink]; var usage: APIUsage }

@MainActor final class APIClient {
    private let session: URLSession
    private let config: ProviderConfig

    init(config: ProviderConfig? = nil) {
        let resolved = config ?? CredentialStore.readConfig()
        self.config = resolved
        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.timeoutIntervalForRequest = 45; sessionConfig.timeoutIntervalForResource = 60
        sessionConfig.httpCookieStorage = nil; sessionConfig.urlCache = nil
        session = URLSession(configuration: sessionConfig, delegate: NoRedirect(), delegateQueue: nil)
    }

    func post(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        guard !config.apiKey.isEmpty else { throw APIError.missingKey }
        let baseURL = config.resolvedBaseURL
        guard !baseURL.isEmpty else { throw APIError.missingKey }
        var request = URLRequest(url: URL(string: baseURL + "/" + path)!)
        request.httpMethod = "POST"; request.setValue("Bearer " + config.apiKey, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw APIError.http(http.statusCode) }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw APIError.invalidResponse }
        return json
    }

    func respond(instructions: String, input: String, schema: [String: Any]? = nil, search: Bool = false) async throws -> APIResult {
        let isOpenAI = config.type == .openai
        let body: [String: Any]
        let endpoint: String
        if isOpenAI {
            body = ["model": config.resolvedModel, "store": false, "instructions": instructions,
                    "input": [["role": "user", "content": input]], "max_output_tokens": schema == nil ? 1400 : 2200,
                    "reasoning": ["effort": "low"]]
            endpoint = "responses"
        } else {
            body = ["model": config.resolvedModel,
                    "messages": [["role": "system", "content": instructions], ["role": "user", "content": input]],
                    "max_tokens": schema == nil ? 1400 : 2200, "temperature": 0.7]
            endpoint = "chat/completions"
        }
        var mutableBody = body
        if let schema {
            if isOpenAI {
                mutableBody["text"] = ["format": ["type": "json_schema", "name": "mural_result", "strict": true, "schema": schema]]
            } else {
                mutableBody["response_format"] = ["type": "json_schema", "json_schema": ["name": "mural_result", "strict": true, "schema": schema]]
            }
        }
        if search && isOpenAI { mutableBody["tools"] = [["type": "web_search"]]; mutableBody["tool_choice"] = "auto"; mutableBody["max_tool_calls"] = 1 }
        let json = try await post(endpoint, body: mutableBody)
        if isOpenAI {
            guard json["status"] as? String == "completed" else { throw APIError.incomplete }
            var text = "", sources: [SourceLink] = [], usage = APIUsage()
            for item in json["output"] as? [[String: Any]] ?? [] {
                if item["type"] as? String == "web_search_call" { usage.searches += 1 }
                for content in item["content"] as? [[String: Any]] ?? [] {
                    if content["type"] as? String == "refusal" { throw APIError.refused }
                    if content["type"] as? String == "output_text" { text += content["text"] as? String ?? "" }
                    for citation in content["annotations"] as? [[String: Any]] ?? [] {
                        guard citation["type"] as? String == "url_citation", let url = citation["url"] as? String else { continue }
                        let source = SourceLink(title: citation["title"] as? String ?? "Source", url: url)
                        if source.safeURL != nil && !sources.contains(where: { $0.url == url }) { sources.append(source) }
                    }
                }
            }
            if let u = json["usage"] as? [String: Any] { usage.input = u["input_tokens"] as? Int ?? 0; usage.output = u["output_tokens"] as? Int ?? 0 }
            guard !text.isEmpty else { throw APIError.incomplete }
            return APIResult(text: text, sources: sources, usage: usage)
        } else {
            guard let choices = json["choices"] as? [[String: Any]], let first = choices.first,
                  let message = first["message"] as? [String: Any],
                  let text = message["content"] as? String, !text.isEmpty else { throw APIError.incomplete }
            if first["finish_reason"] as? String == "length" { throw APIError.incomplete }
            var usage = APIUsage()
            if let u = json["usage"] as? [String: Any] { usage.input = u["prompt_tokens"] as? Int ?? 0; usage.output = u["completion_tokens"] as? Int ?? 0 }
            return APIResult(text: text, sources: [], usage: usage)
        }
    }
    static func object(_ fields: [String: Any]) -> [String: Any] { ["type": "object", "properties": fields, "required": fields.keys.sorted(), "additionalProperties": false] }
    static let string: [String: Any] = ["type": "string"]
    static func assessmentSchema(language: LanguageModule) -> [String: Any] { object([
        "outcome": ["type": "string", "enum": ["success", "partial", "breakdown", "uncertain"]],
        "suggestedLevel": ["type": "integer", "minimum": 0, "maximum": 5], "nextGoal": string, "capability": string,
        "words": ["type": "array", "maxItems": 12, "items": object([
            "lemma": string, "meaning": string, "form": string, "quote": string, "language": ["type": "string", "enum": Array(Set([language.id, "en", "mixed", "uncertain"])).sorted()],
            "kind": ["type": "string", "enum": ["exposure", "understanding", "assisted", "independent", "lapse"]],
            "confidence": ["type": "number", "minimum": 0, "maximum": 1], "sourceIDs": ["type": "array", "items": string]
        ])]
    ]) }
    enum APIError: LocalizedError {
        case missingKey, invalidResponse, incomplete, refused, http(Int)
        var errorDescription: String? {
            switch self {
            case .missingKey: "Add your API key in Settings to begin."
            case .invalidResponse, .incomplete: "The API returned an incomplete response. Please try again."
            case .refused: "Mural couldn't complete that request. Try a different topic."
            case .http(401): "Your API key wasn't accepted. Check it in Settings."
            case .http(403), .http(404): "This API key may not have access to the requested model. Check your provider settings."
            case .http(429): "Usage or rate limit was reached. Check your provider's billing and limits."
            case .http(let status): "The API couldn't complete the request (HTTP \(status)). Please try again."
            }
        }
    }
}
