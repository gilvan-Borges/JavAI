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
    /** Tentativas por pergunta. O retry só acontece antes do primeiro pedaço chegar: depois disso repetiria texto. */
    private static final int ATTEMPTS = 3;
    private static final Duration[] BACKOFF = {Duration.ofMillis(400), Duration.ofMillis(1200)};
    /**
     * Prazo total da resposta, somando as tentativas e a leitura do stream. Cabe dentro do tempo do stream do chat
     * ({@code ChatController.STREAM_TIMEOUT_MS}), com folga para classificar, buscar e fechar a guarda de saída.
     */
    static final Duration TOTAL_BUDGET = Duration.ofSeconds(70);
    /** Espera máxima pelos cabeçalhos da resposta em cada tentativa. */
    static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(20);
    /** Fecha a conexão com o provedor quando o prazo acaba: é o que destrava um readLine parado. */
    private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "openrouter-deadline");
                t.setDaemon(true);
                return t;
            });

    private final HttpClient http;
    private final String baseUrl;
    private final String key;
    private final int maxTokens;
    private final BigDecimal maxPriceCompletion;
    private final boolean zdr;
    private final String reasoningEffort;
    private final Duration budget;

    public OpenRouterClient(HttpClient http, String baseUrl, String key, int maxTokens, BigDecimal maxPriceCompletion, boolean zdr,
                            String reasoningEffort) {
        this(http, baseUrl, key, maxTokens, maxPriceCompletion, zdr, reasoningEffort, TOTAL_BUDGET);
    }

    /** Com prazo total próprio: os testes usam um prazo curto para não esperar o de produção. */
    OpenRouterClient(HttpClient http, String baseUrl, String key, int maxTokens, BigDecimal maxPriceCompletion, boolean zdr,
                     String reasoningEffort, Duration budget) {
        this.budget = budget;
        this.http = http;
        this.baseUrl = baseUrl;
        this.key = key;
        this.maxTokens = maxTokens;
        this.maxPriceCompletion = maxPriceCompletion;
        this.zdr = zdr;
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
    }

    @Override
    public Usage stream(PromptBuilder.Prompt prompt, List<String> models, Predicate<String> onDelta) throws IOException {
        // Falha transitória (rede, 408, 429, 5xx) é retentada com espera crescente, mas SÓ enquanto nenhum pedaço
        // foi entregue: retentar depois disso duplicaria texto na tela. Erro de configuração (401, 402...) não retenta.
        boolean[] delivered = {false};
        long deadline = System.nanoTime() + budget.toNanos();
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return attempt(prompt, models, deadline, delta -> {
                    delivered[0] = true;
                    return onDelta.test(delta);
                });
            } catch (RetryableException e) {
                last = e;
                Duration wait = attempt < ATTEMPTS ? BACKOFF[attempt - 1] : Duration.ZERO;
                if (delivered[0] || attempt == ATTEMPTS || remaining(deadline).compareTo(wait.plusSeconds(2)) < 0) {
                    throw e;
                }
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrompido.", ie);
                }
            }
        }
        throw last; // inalcançável; o compilador não sabe
    }

    /** Transitória: vale tentar de novo. A mensagem nunca carrega o corpo da resposta do provedor. */
    private static final class RetryableException extends IOException {
        RetryableException(String message) {
            super(message);
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }

    private Usage attempt(PromptBuilder.Prompt prompt, List<String> models, long deadline, Predicate<String> onDelta)
            throws IOException {
        Duration left = remaining(deadline);
        if (left.isZero()) {
            throw new IOException("O modelo passou do prazo total.");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(left.compareTo(HEADERS_TIMEOUT) < 0 ? left : HEADERS_TIMEOUT)
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
        } catch (IOException e) {
            throw new RetryableException("Não consegui falar com o OpenRouter.");
        }
        int status = response.statusCode();
        if (status != 200) {
            response.body().close();
            String message = "OpenRouter respondeu HTTP " + status;
            if (status == 408 || status == 429 || status >= 500) {
                throw new RetryableException(message);
            }
            throw new IOException(message);
        }

        String model = models.get(0);
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        // Fechar o reader fecha a conexão: é assim que o cancelamento chega ao provedor. O mesmo fechamento, agendado
        // para o fim do prazo, destrava um stream parado (o provedor manda comentários de keep-alive indefinidamente).
        var body = response.body();
        var expired = new java.util.concurrent.atomic.AtomicBoolean();
        boolean completed = false;
        var timer = DEADLINES.schedule(() -> {
            expired.set(true);
            try {
                body.close();
            } catch (IOException ignored) {
                // nada a fazer: o objetivo era só derrubar a leitura
            }
        }, Math.max(1, remaining(deadline).toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        try (var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
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
                    // Se nada foi entregue ainda, o chamador retenta; se já foi, a exceção sobe como está.
                    throw new RetryableException("O provedor devolveu erro no meio do stream.");
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
            // Fechado pelo prazo, o stream pode terminar como fim de arquivo em vez de erro: isso não é resposta completa.
            completed = !expired.get();
        } catch (IOException e) {
            if (expired.get()) {
                throw new IOException("O modelo passou do prazo total.", e);
            }
            throw e;
        } finally {
            timer.cancel(false);
        }
        if (!completed && expired.get()) {
            throw new IOException("O modelo passou do prazo total.");
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
        if (!reasoningEffort.isBlank()) {
            body.put("reasoning", Map.of("effort", reasoningEffort));
        }
        body.put("provider", provider);
        return body;
    }
}
