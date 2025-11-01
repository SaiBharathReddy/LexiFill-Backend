package com.lexifill.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HuggingFaceService {

    @Value("${HUGGINGFACE_API_TOKEN}")
    private String apiToken;

    private static final String API_URL = "https://router.huggingface.co/v1/chat/completions";
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Map<String, String>> extractPlaceholders(String docText) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiToken);

        // --- SYSTEM PROMPT ---
        String systemPrompt = """
You are a document parser that extracts ALL fillable placeholders from documents.

DEFINITION: A fillable placeholder is a blank space or marked field where a user must enter custom information. Think you are a user and ask yourself if you need to fill that field or not.

RECOGNIZE PLACEHOLDERS BY THESE PATTERNS:
1. Bracketed text: [Company Name], [Date], [Amount], [Address], [Email]
2. Dollar amount blanks: $[_____], $[_____________]
3. Simple underscores: _____, ____________
4. Signature fields: [name], [title], [Address], [Email] (often appear in pairs for different parties)

SCAN THOROUGHLY:
- Read the ENTIRE document from start to finish
- Pay special attention to: headers, body text, financial terms, and signature blocks
- Signature blocks typically contain: name, title, address, email for each signing party

CRITICAL: A placeholder must be EMPTY or contain GENERIC descriptive text.
It should NOT be:
- Section references: "Section 2", "See Section X"
- Document labels used as headers: "INVESTOR", "COMPANY" (unless bracketed like [COMPANY])
- Version numbers: "Version 1.2"
- Document type labels: "POST-MONEY VALUATION CAP" (unless it's a blank to fill)

CRITICAL ORDER REQUIREMENT: Extract placeholders in the EXACT ORDER they appear in the document from top to bottom.
- Start from the first line and work down to the last line. The order should strictly match the document.

EXTRACTION RULES:
1. Extract the placeholder exactly as it appears (including $, brackets, underscores)
2. Strip surrounding labels: "Amount: $[____]" → extract "$[____]"
3. For duplicate placeholders (like [name] appearing twice), extract each occurrence and differentiate in the question
4. Do NOT skip any placeholders - extract every single fillable field

OUTPUT FORMAT:
Return ONLY a valid JSON array. No explanations or notes.

Example:
Document: "Payment of $[____] on [Date]. COMPANY: Name:[name] Title:[title] Address:[Address] INVESTOR: Name:[name] Title:[title]"

Output:
[
  {"placeholder":"$[____]", "question":"What is the payment amount?"},
  {"placeholder":"[Date]", "question":"What is the date?"},
  {"placeholder":"[name]", "question":"What is the name of the person signing for the company?"},
  {"placeholder":"[title]", "question":"What is the title of the person signing for the company?"},
  {"placeholder":"[Address]", "question":"What is the company's address?"},
  {"placeholder":"[name]", "question":"What is the name of the person signing for the investor?"},
  {"placeholder":"[title]", "question":"What is the title of the person signing for the investor?"}
]

Begin your response with [
""";


        String userPrompt = "Document Text:\n" + docText;

        Map<String, Object> body = new HashMap<>();
        body.put("model", "meta-llama/Llama-3.1-8B-Instruct");
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        body.put("temperature", 0.0);
        body.put("max_tokens", 2500);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> resp = restTemplate.exchange(API_URL, HttpMethod.POST, entity, String.class);

        if (resp.getBody() == null) {
            throw new RuntimeException("Empty response from Hugging Face API");
        }

        JsonNode root = objectMapper.readTree(resp.getBody());
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return new ArrayList<>();
        }

        String content = choices.get(0).path("message").path("content").asText().trim();
        //System.out.println("LLM raw output: " + content);

        // Extract clean JSON
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start == -1 || end == -1) {
            throw new RuntimeException("No JSON array found in LLM response: " + content);
        }

        String jsonArray = content.substring(start, end + 1);

        // Fix smart quotes (LLMs sometimes use them)
        jsonArray = jsonArray
                .replace("\u201C", "\"")
                .replace("\u201D", "\"")
                .replace("\u2018", "'")
                .replace("\u2019", "'");

        try {
            return objectMapper.readValue(jsonArray, new TypeReference<>() {});
        } catch (Exception e) {
            System.err.println("Failed to parse JSON: " + e.getMessage());
            throw new RuntimeException("Invalid JSON from LLM: " + jsonArray);
        }
    }
}
