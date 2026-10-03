package br.com.gilvanborges.javai.ai.api.chat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Cliente do OpenRouter feito com {@code java.net.http.HttpClient}, por dois motivos (spec 01): o JavAI usa campos que
 * só o OpenRouter tem ({@code models}, {@code provider}, {@code max_price}) e a guarda de saída precisa controlar o
 * streaming, inclusive cancelá-lo no meio. O modelo nunca vem do visitante: a lista chega da configuração.
 */
public class OpenRouterClient implements ChatModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String baseUrl;
    private final String key;
    private final int maxTokens;
    private final BigDecimal maxPriceCompletion;
    private final boolean zdr;

    public OpenRouterClient(HttpClient http, String baseUrl, String key, int maxTokens, BigDecimal maxPriceCompletion, boolean zdr) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.key = key;
        this.maxTokens = maxTokens;
        this.maxPriceCompletion = maxPriceCompletion;
        this.zdr = zdr;
    }

    @Override
    public Usage stream(PromptBuilder.Prompt prompt, List<String> models, Predicate<String> onDelta) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .header("X-Title", "JavAI")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body(prompt, models))))
                .build();
        HttpResponse<java.io.InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompido.", e);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("OpenRouter respondeu HTTP " + response.statusCode());
        }

        String model = models.get(0);
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        // Fechar o reader fecha a conexão: é assim que o cancelamento chega ao provedor.
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue; // comentários de keep-alive (": OPENROUTER PROCESSING") e linhas vazias
                }
                String data = line.substring(5).strip();
                if (data.equals("[DONE]")) {
                    break;
                }
                JsonNode node = JSON.readTree(data);
                if (node.has("error")) {
                    throw new IOException("O provedor devolveu erro no meio do stream.");
                }
                model = node.path("model").asString(model);
                JsonNode usage = node.path("usage");
                if (usage.isObject()) {
                    in = usage.path("prompt_tokens").asLong(in);
                    out = usage.path("completion_tokens").asLong(out);
                    cost = usage.path("cost").isNumber() ? usage.path("cost").decimalValue() : cost;
                }
                String delta = node.path("choices").path(0).path("delta").path("content").asString("");
                if (!delta.isEmpty() && !onDelta.test(delta)) {
                    break;
                }
            }
        }
        return new Usage(model, in, out, cost);
    }

    private Map<String, Object> body(PromptBuilder.Prompt prompt, List<String> models) {
        var provider = new LinkedHashMap<String, Object>();
        provider.put("data_collection", "deny");
        if (zdr) {
            provider.put("zdr", true);
        }
        if (maxPriceCompletion.signum() > 0) {
            provider.put("max_price", Map.of("completion", maxPriceCompletion));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("models", models);
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.system()),
                Map.of("role", "user", "content", prompt.user())));
        body.put("stream", true);
        body.put("max_tokens", maxTokens);
        body.put("usage", Map.of("include", true));
        body.put("provider", provider);
        return body;
    }
}
