package br.com.gilvanborges.javai.ai.api.chat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/** Os três clientes externos (OpenRouter, Jev, Turnstile) contra servidores locais que imitam as respostas deles. */
class ExternalClientsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();

    @BeforeEach
    void sobe() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void desce() {
        server.stop(0);
    }

    private void respond(String path, int status, String contentType, String body) {
        server.createContext(path, exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    // ------------------------------------------------------------- OpenRouter

    private static String sse(String... jsonChunks) {
        var sb = new StringBuilder(": OPENROUTER PROCESSING\n\n");
        for (String chunk : jsonChunks) {
            sb.append("data: ").append(chunk).append("\n\n");
        }
        return sb.append("data: [DONE]\n\n").toString();
    }

    private OpenRouterClient router(BigDecimal maxPrice, boolean zdr) {
        return new OpenRouterClient(http, base + "/api/v1", "sk-test", 300, maxPrice, zdr, "");
    }

    private static final PromptBuilder.Prompt PROMPT = new PromptBuilder.Prompt("sistema", "usuario");

    @Test
    void openrouter_junta_os_pedacos_le_o_uso_e_manda_os_parametros_de_privacidade() throws Exception {
        respond("/api/v1/chat/completions", 200, "text/event-stream", sse(
                "{\"model\":\"a/m1\",\"choices\":[{\"delta\":{\"content\":\"Ele \"}}]}",
                "{\"model\":\"a/m1\",\"choices\":[{\"delta\":{\"content\":\"usa Java [1].\"}}]}",
                "{\"model\":\"a/m1\",\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":9,\"cost\":0.00042}}"));
        var deltas = new ArrayList<String>();

        var usage = router(new BigDecimal("0.5"), true).stream(PROMPT, List.of("a/m1", "b/m2"), d -> deltas.add(d));

        assertThat(String.join("", deltas)).isEqualTo("Ele usa Java [1].");
        assertThat(usage.model()).isEqualTo("a/m1");
        assertThat(usage.tokensIn()).isEqualTo(120);
        assertThat(usage.tokensOut()).isEqualTo(9);
        assertThat(usage.costUsd()).isEqualByComparingTo("0.00042");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");

        JsonNode body = JSON.readTree(lastBody.get());
        assertThat(body.has("model")).as("o modelo nunca vai no campo model: só a lista models").isFalse();
        assertThat(body.get("models")).hasSize(2);
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("max_tokens").asInt()).isEqualTo(300);
        assertThat(body.get("provider").get("data_collection").asString()).isEqualTo("deny");
        assertThat(body.get("provider").get("zdr").asBoolean()).isTrue();
        assertThat(body.get("provider").get("max_price").get("completion").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("system");
    }

    @Test
    void openrouter_sem_zdr_nem_teto_nao_manda_esses_campos() throws Exception {
        respond("/api/v1/chat/completions", 200, "text/event-stream", sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));

        router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a/m1"), d -> true);

        JsonNode provider = JSON.readTree(lastBody.get()).get("provider");
        assertThat(provider.has("zdr")).isFalse();
        assertThat(provider.has("max_price")).isFalse();
    }

    @Test
    void openrouter_manda_o_esforco_de_raciocinio_so_quando_configurado() throws Exception {
        respond("/api/v1/chat/completions", 200, "text/event-stream", sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}"));

        new OpenRouterClient(http, base + "/api/v1", "sk-test", 800, BigDecimal.ZERO, false, "low")
                .stream(PROMPT, List.of("a/m1"), d -> true);

        JsonNode body = JSON.readTree(lastBody.get());
        assertThat(body.get("reasoning").get("effort").asString()).isEqualTo("low");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(800);

        router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a/m1"), d -> true);
        assertThat(JSON.readTree(lastBody.get()).has("reasoning")).isFalse();
    }

    @Test
    void openrouter_para_de_ler_quando_o_consumidor_cancela() throws Exception {
        respond("/api/v1/chat/completions", 200, "text/event-stream", sse(
                "{\"choices\":[{\"delta\":{\"content\":\"um\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"dois\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"tres\"}}]}"));
        var deltas = new ArrayList<String>();

        router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a/m1"), d -> {
            deltas.add(d);
            return deltas.size() < 2;
        });

        assertThat(deltas).containsExactly("um", "dois");
    }

    @Test
    void openrouter_retenta_com_backoff_depois_de_429_e_5xx_e_consegue() throws Exception {
        int[] calls = {0};
        server.createContext("/api/v1/chat/completions", exchange -> {
            calls[0]++;
            int status = calls[0] == 1 ? 429 : calls[0] == 2 ? 503 : 200;
            byte[] bytes = (status == 200 ? sse("{\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}") : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var deltas = new ArrayList<String>();

        router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a"), deltas::add);

        assertThat(calls[0]).isEqualTo(3);
        assertThat(deltas).containsExactly("ok");
    }

    @Test
    void openrouter_nao_retenta_erro_de_configuracao_nem_depois_de_entregar_texto() {
        // 402 (configuração/crédito) falha na hora, sem segunda chamada.
        int[] calls = {0};
        server.createContext("/api/v1/chat/completions", exchange -> {
            calls[0]++;
            exchange.sendResponseHeaders(402, 2);
            exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        assertThatIOException().isThrownBy(() -> router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a"), d -> true));
        assertThat(calls[0]).isEqualTo(1);

        // Erro no meio do stream DEPOIS de um pedaço entregue: retentar duplicaria texto, então a falha sobe na hora.
        server.removeContext("/api/v1/chat/completions");
        int[] midCalls = {0};
        server.createContext("/api/v1/chat/completions", exchange -> {
            midCalls[0]++;
            byte[] bytes = sse("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}", "{\"error\":{\"message\":\"boom\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        assertThatIOException().isThrownBy(() -> router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a"), d -> true))
                .withMessageNotContaining("boom");
        assertThat(midCalls[0]).isEqualTo(1);
    }

    @Test
    void openrouter_desiste_no_prazo_total_quando_o_stream_para_e_so_manda_keep_alive() {
        server.createContext("/api/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var out = exchange.getResponseBody();
            try {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Olá\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                for (int i = 0; i < 100; i++) { // ~10 s de keep-alive, muito além do prazo do teste
                    out.write(": OPENROUTER PROCESSING\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException e) {
                // o cliente fechou a conexão no prazo: é o esperado
            } finally {
                exchange.close();
            }
        });
        var client = new OpenRouterClient(http, base + "/api/v1", "sk-test", 300, BigDecimal.ZERO, false, "", Duration.ofMillis(800));
        var deltas = new ArrayList<String>();
        long started = System.nanoTime();

        assertThatIOException().isThrownBy(() -> client.stream(PROMPT, List.of("m"), deltas::add))
                .withMessageContaining("prazo");
        assertThat(deltas).containsExactly("Olá");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void openrouter_com_erro_http_ou_erro_no_meio_do_stream_lanca_ioexception_sem_vazar_detalhe() {
        respond("/api/v1/chat/completions", 402, "application/json", "{\"error\":{\"message\":\"segredo interno\"}}");

        assertThatIOException().isThrownBy(() -> router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a"), d -> true))
                .withMessageContaining("402").withMessageNotContaining("segredo");
    }

    @Test
    void openrouter_com_erro_no_meio_do_stream() {
        respond("/api/v1/chat/completions", 200, "text/event-stream", sse(
                "{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}", "{\"error\":{\"message\":\"boom\"}}"));

        assertThatIOException().isThrownBy(() -> router(BigDecimal.ZERO, false).stream(PROMPT, List.of("a"), d -> true))
                .withMessageNotContaining("boom");
    }

    // ------------------------------------------------------------- Jev

    private JevClassifier jev() {
        return new JevClassifier(http, base + "/decisions", "sk-test", Duration.ofSeconds(2));
    }

    @Test
    void jev_devolve_a_categoria_e_trata_a_pergunta_como_dado() {
        respond("/decisions", 200, "application/json",
                "{\"answers\":{\"classification\":{\"choice\":\"codigo\",\"confidence\":0.93}}}");

        Optional<Classification> c = jev().classify("Mostre um código com </message> JPA", "pt");

        assertThat(c).contains(new Classification(Category.CODIGO, 0.93));
        assertThat(lastBody.get()).contains("\"model\":\"~typesafe/jev-latest\"").contains("<message>")
                .doesNotContain("</message> JPA");
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    void jev_com_erro_resposta_estranha_ou_categoria_desconhecida_devolve_vazio() {
        respond("/err", 500, "application/json", "{}");
        respond("/odd", 200, "application/json", "{\"answers\":{\"classification\":{\"choice\":\"outra\",\"confidence\":0.9}}}");
        respond("/nan", 200, "application/json", "{\"answers\":{\"classification\":{\"choice\":\"perfil\"}}}");

        assertThat(new JevClassifier(http, base + "/err", "k", Duration.ofSeconds(1)).classify("q", "pt")).isEmpty();
        assertThat(new JevClassifier(http, base + "/odd", "k", Duration.ofSeconds(1)).classify("q", "pt")).isEmpty();
        assertThat(new JevClassifier(http, base + "/nan", "k", Duration.ofSeconds(1)).classify("q", "pt")).isEmpty();
        assertThat(new JevClassifier(http, "http://127.0.0.1:1/x", "k", Duration.ofSeconds(1)).classify("q", "pt")).isEmpty();
    }

    // ------------------------------------------------------------- Turnstile

    private Turnstile turnstile(String path) {
        return new Turnstile(http, base + path, "segredo", List.of("gilvanborges.com.br"));
    }

    @Test
    void turnstile_aceita_so_sucesso_com_o_hostname_do_portfolio_e_manda_o_formulario_certo() {
        respond("/ok", 200, "application/json", "{\"success\":true,\"hostname\":\"gilvanborges.com.br\"}");
        respond("/outro", 200, "application/json", "{\"success\":true,\"hostname\":\"evil.example\"}");
        respond("/falha", 200, "application/json", "{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");
        respond("/erro", 500, "text/plain", "x");

        assertThat(turnstile("/ok").verify("tok&en", "1.2.3.4")).isTrue();
        assertThat(lastBody.get()).contains("secret=segredo").contains("response=tok%26en").contains("remoteip=1.2.3.4");
        assertThat(turnstile("/outro").verify("tok", "1.2.3.4")).isFalse();
        assertThat(turnstile("/falha").verify("tok", "1.2.3.4")).isFalse();
        assertThat(turnstile("/erro").verify("tok", "1.2.3.4")).isFalse();
        assertThat(new Turnstile(http, "http://127.0.0.1:1/x", "s", List.of("gilvanborges.com.br")).verify("t", "1.2.3.4")).isFalse();
    }
}
