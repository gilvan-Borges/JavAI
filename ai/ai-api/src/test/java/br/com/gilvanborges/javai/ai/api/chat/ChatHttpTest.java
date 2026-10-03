package br.com.gilvanborges.javai.ai.api.chat;

import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.IndexRepository;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O chat de ponta a ponta no Tomcat, com o Turnstile, o Jev e o OpenRouter trocados por servidores locais: o que o
 * portfólio enxerga (status, Problem Details, SSE, CORS), com o conteúdo do índice vindo de um dublê.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiapi.db.enabled=false",
        "aiapi.model-dir=/nao-existe",
        "aiapi.chat.openrouter-key=sk-test",
        "aiapi.chat.models-fast=rapido/a,rapido/b",
        "aiapi.chat.models-strong=forte/a,forte/b",
        "aiapi.chat.turnstile-secret=segredo",
        "aiapi.chat.history-secret=segredo-de-teste",
        "aiapi.chat.per-minute=3",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "management.server.port=0"})
@Import(ChatHttpTest.Stub.class)
class ChatHttpTest {

    @TestConfiguration
    static class Stub {
        @Bean
        IndexRepository indexRepository() {
            return ApiFixtures.repository(ApiFixtures.index());
        }

        @Bean
        UsageStore usageStore() {
            return STORE;
        }
    }

    static final ApiFixtures.MemoryStore STORE = new ApiFixtures.MemoryStore();
    static HttpServer fake;

    static {
        try {
            fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        fake.createContext("/ts", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String json = form.contains("response=good")
                    ? "{\"success\":true,\"hostname\":\"gilvanborges.com.br\"}" : "{\"success\":false}";
            reply(exchange, "application/json", json);
        });
        fake.createContext("/decisions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            reply(exchange, "application/json", "{\"answers\":{\"classification\":{\"choice\":\"codigo\",\"confidence\":0.9}}}");
        });
        fake.createContext("/or/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            reply(exchange, "text/event-stream",
                    "data: {\"model\":\"forte/a\",\"choices\":[{\"delta\":{\"content\":\"Ele usa RabbitMQ \"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\"no apiSuporte [1].\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":12,\"cost\":0.0003}}\n\n"
                            + "data: [DONE]\n\n");
        });
        fake.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + fake.getAddress().getPort();
        registry.add("aiapi.chat.turnstile-url", () -> base + "/ts");
        registry.add("aiapi.chat.jev-url", () -> base + "/decisions");
        registry.add("aiapi.chat.openrouter-base", () -> base + "/or");
    }

    @AfterAll
    static void para() {
        fake.stop(0);
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static int ipSeq = 0;

    @LocalServerPort
    int port;

    @Autowired
    IndexHolder holder;

    @BeforeEach
    void carrega() {
        holder.refresh();
    }

    private HttpResponse<String> post(String body, String ip, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat"))
                .header("Content-Type", "application/json").header("X-Real-IP", ip)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.setHeader(headers[i], headers[i + 1]);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String freshIp() {
        return "10.1." + (++ipSeq / 200) + "." + (ipSeq % 200 + 1);
    }

    private static int qSeq = 0;

    /** Cada chamada ganha um sufixo único: o contexto Spring (e o cache de respostas) é um só para a classe toda. */
    private static String good(String question) {
        return "{\"question\":\"" + question + " v" + (++qSeq) + "\",\"lang\":\"pt\",\"turnstileToken\":\"good\"}";
    }

    @Test
    void pergunta_valida_devolve_sse_com_meta_delta_sources_e_done() throws Exception {
        var response = post(good("Ele já usou RabbitMQ?"), freshIp());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        String body = response.body();
        assertThat(body).contains("event:meta").contains("\"category\":\"codigo\"").contains("\"tier\":\"forte\"");
        assertThat(body).contains("event:delta").contains("Ele usa RabbitMQ ");
        assertThat(body).contains("event:sources").contains("\"ref\":1").contains("https://github.com/");
        assertThat(body).contains("event:done").contains("\"sig\":").contains("\"tokensIn\":100");
        assertThat(body.indexOf("event:meta")).isLessThan(body.indexOf("event:done"));
    }

    @Test
    void campo_model_no_corpo_e_400_em_problem_details() throws Exception {
        var response = post("{\"question\":\"oi tudo bem\",\"turnstileToken\":\"good\",\"model\":\"gpt-5\"}", freshIp());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        assertThat(response.body()).contains("/problems/validation").doesNotContain("Exception").doesNotContain("at br.com");
    }

    @Test
    void pergunta_com_501_caracteres_e_400() throws Exception {
        var response = post("{\"question\":\"" + "a".repeat(501) + "\",\"turnstileToken\":\"good\"}", freshIp());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void corpo_que_nao_e_json_e_415() throws Exception {
        var response = post(good("Ele já usou RabbitMQ?"), freshIp(), "Content-Type", "text/plain");

        assertThat(response.statusCode()).isEqualTo(415);
    }

    @Test
    void token_do_turnstile_invalido_e_403() throws Exception {
        var response = post("{\"question\":\"Ele já usou RabbitMQ?\",\"turnstileToken\":\"bad\"}", freshIp());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("/problems/turnstile");
    }

    @Test
    void quarta_pergunta_do_mesmo_ip_no_minuto_e_429_com_retry_after() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 3; i++) {
            assertThat(post(good("Ele já usou RabbitMQ?"), ip).statusCode()).isEqualTo(200);
        }

        var limited = post(good("Ele já usou RabbitMQ?"), ip);

        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        assertThat(limited.body()).contains("/problems/rate-limited");
    }

    @Test
    void cors_vale_so_para_o_portfolio_nas_rotas_v1() throws Exception {
        var preflight = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat"))
                .header("Origin", "https://gilvanborges.com.br").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var ok = HTTP.send(preflight, HttpResponse.BodyHandlers.ofString());

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).contains("https://gilvanborges.com.br");
        assertThat(ok.headers().firstValue("Access-Control-Max-Age")).contains("600");
        assertThat(ok.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();

        var evil = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat"))
                .header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(evil.statusCode()).isEqualTo(403);
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void health_libera_cors_so_para_o_portfolio_porque_o_ponto_verde_do_terminal_depende_disso() throws Exception {
        var fromPortfolio = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .header("Origin", "https://gilvanborges.com.br").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(fromPortfolio.statusCode()).isEqualTo(200);
        assertThat(fromPortfolio.headers().firstValue("Access-Control-Allow-Origin")).contains("https://gilvanborges.com.br");

        var fromEvil = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString());
        assertThat(fromEvil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();

        // Só GET: um POST de outra origem não ganha cabeçalho nenhum.
        var post = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .header("Origin", "https://gilvanborges.com.br").header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void health_e_stats_sao_publicos_e_agregados() throws Exception {
        var health = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"ok\"");

        post(good("Ele já usou RabbitMQ?"), freshIp());
        var stats = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/stats")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(stats.statusCode()).isEqualTo(200);
        assertThat(stats.headers().firstValue("Cache-Control").orElse("")).contains("max-age=3600").contains("public");
        assertThat(stats.body()).contains("\"questions\":").contains("\"answersByModel\":").doesNotContain("RabbitMQ");
    }
}
