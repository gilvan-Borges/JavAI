package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.IndexRepository;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.search.ScalarVectorOps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** O /admin de ponta a ponta no Tomcat: login, sessão, bloqueio de força bruta, cabeçalhos e escape de HTML. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiapi.db.enabled=false",
        "aiapi.model-dir=/nao-existe",
        "aiapi.admin.user=gilvan",
        "aiapi.admin.password=senha-de-teste-bem-longa-123",
        "aiapi.chat.openrouter-key=sk-or-segredo-que-nao-pode-aparecer",
        "aiapi.chat.models-fast=openai/gpt-6.1-sol",
        "aiapi.chat.models-strong=openai/gpt-6.1-sol",
        "aiapi.chat.turnstile-secret=segredo-do-turnstile",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "management.server.port=0"})
@Import(AdminHttpTest.Stub.class)
class AdminHttpTest {

    static final String XSS = "<script>alert('xss')</script>";

    @TestConfiguration
    static class Stub {
        @Bean
        IndexRepository indexRepository() {
            var chunks = new ArrayList<Chunk>(ApiFixtures.index().chunks());
            chunks.add(new Chunk("en:x:1", SourceKind.REPO_README, "repo_readme:dono/repo:README.md:" + XSS, "en", "README " + XSS,
                    "corpo " + XSS + " fim", "https://github.com/dono/repo"));
            var index = new ActiveIndex(1, "multilingual-e5-small-int8", chunks, null, ApiFixtures.index().evidence(), new ScalarVectorOps());
            return new IndexRepository() {
                @Override
                public Optional<Long> activeVersionId() {
                    return Optional.of(1L);
                }

                @Override
                public Optional<VersionMeta> activeMeta() {
                    return Optional.of(new VersionMeta(1, java.time.Instant.parse("2026-10-03T06:00:00Z"), "multilingual-e5-small-int8",
                            "{\"pt\":\"\\\"a\\\"\",\"repo:dono/repo\":\"0123456789abcdef0123456789abcdef01234567\"}",
                            "{\"repositoriosPulados\":[\"dono/fork: repositório é fork\"],\"avisos\":[\"x: arquivo pulado\"],\"evidencias\":2}"));
                }

                @Override
                public Optional<ActiveIndex> loadActive() {
                    return Optional.of(index);
                }
            };
        }

        @Bean
        UsageStore usageStore() {
            return new ApiFixtures.MemoryStore();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static int ipSeq;

    @LocalServerPort
    int port;

    @Autowired
    IndexHolder holder;

    @BeforeEach
    void carrega() {
        holder.refresh();
    }

    private static String freshIp() {
        return "10.20." + (++ipSeq / 200) + "." + (ipSeq % 200 + 1);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (cookie != null) {
            b.header("Cookie", cookie);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String user, String password, String ip, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/login"))
                .header("Content-Type", "application/x-www-form-urlencoded").header("X-Real-IP", ip)
                .POST(HttpRequest.BodyPublishers.ofString("username=" + URLEncoder.encode(user, StandardCharsets.UTF_8)
                        + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8)));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String sessionCookie() throws Exception {
        var response = login("gilvan", "senha-de-teste-bem-longa-123", freshIp());
        assertThat(response.statusCode()).isEqualTo(303);
        String set = response.headers().firstValue("Set-Cookie").orElseThrow();
        return set.substring(0, set.indexOf(';'));
    }

    @Test
    void sem_sessao_tudo_redireciona_para_o_login() throws Exception {
        for (String path : List.of("/admin", "/admin/trechos", "/admin/busca?q=rabbitmq")) {
            var response = get(path, null);
            assertThat(response.statusCode()).as(path).isEqualTo(303);
            assertThat(response.headers().firstValue("Location")).as(path).contains("/admin/login");
            assertThat(response.body()).as(path).doesNotContain("RabbitMQ");
        }
    }

    @Test
    void cookie_forjado_ou_vencido_nao_vale() throws Exception {
        assertThat(get("/admin", "javai_admin=9999999999.assinatura-falsa").statusCode()).isEqualTo(303);
        assertThat(get("/admin", "javai_admin=1.assinatura").statusCode()).isEqualTo(303);
        assertThat(get("/admin", "javai_admin=lixo").statusCode()).isEqualTo(303);
    }

    @Test
    void tela_de_login_e_todas_as_respostas_saem_com_cabecalhos_de_seguranca() throws Exception {
        var response = get("/admin/login", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("name=\"password\"").doesNotContain("<script");
        var headers = response.headers();
        assertThat(headers.firstValue("Content-Security-Policy").orElse("")).contains("default-src 'none'").contains("frame-ancestors 'none'");
        assertThat(headers.firstValue("Cache-Control")).contains("no-store");
        assertThat(headers.firstValue("X-Frame-Options")).contains("DENY");
        assertThat(headers.firstValue("X-Robots-Tag").orElse("")).contains("noindex");
        assertThat(headers.firstValue("Referrer-Policy")).contains("no-referrer");
    }

    @Test
    void senha_errada_da_401_com_mensagem_generica_e_certa_entra_com_cookie_seguro() throws Exception {
        String ip = freshIp();

        var wrong = login("gilvan", "senha-errada", ip);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("Usuário ou senha inválidos.");
        assertThat(login("outro", "senha-de-teste-bem-longa-123", ip).body()).contains("Usuário ou senha inválidos.");

        var ok = login("gilvan", "senha-de-teste-bem-longa-123", ip);
        assertThat(ok.statusCode()).isEqualTo(303);
        assertThat(ok.headers().firstValue("Location")).contains("/admin");
        String cookie = ok.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict").contains("Path=/admin").contains("Max-Age=28800");
    }

    @Test
    void cinco_erros_bloqueiam_o_ip_ate_com_a_senha_certa() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 5; i++) {
            assertThat(login("gilvan", "errada-" + i, ip).statusCode()).isEqualTo(401);
        }

        var locked = login("gilvan", "senha-de-teste-bem-longa-123", ip);

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(locked.headers().firstValue("Set-Cookie")).isEmpty();
        // Outro IP não é afetado.
        assertThat(login("gilvan", "senha-de-teste-bem-longa-123", freshIp()).statusCode()).isEqualTo(303);
    }

    @Test
    void login_vindo_de_outro_site_e_recusado() throws Exception {
        var response = login("gilvan", "senha-de-teste-bem-longa-123", freshIp(), "Sec-Fetch-Site", "cross-site");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void visao_geral_mostra_o_que_a_ia_acessa_sem_vazar_segredo() throws Exception {
        var response = get("/admin", sessionCookie());

        assertThat(response.statusCode()).isEqualTo(200);
        String page = response.body();
        assertThat(page).contains("O que a IA pode consultar").contains("versão 1").contains("multilingual-e5-small-int8");
        assertThat(page).contains("Repositórios do GitHub que a IA leu").contains("dono/repo").contains("0123456");
        assertThat(page).contains("dono/fork: repositório é fork");
        assertThat(page).contains("Treinamento (fine-tuning)").contains("Nenhum.");
        assertThat(page).contains("Never write URLs").contains("‹canário da versão do prompt›");
        assertThat(page).contains("openai/gpt-6.1-sol");
        assertThat(page).contains("RabbitMQ");
        // O que nunca pode aparecer: a chave, o segredo do Turnstile, a senha do admin e o canário real.
        assertThat(page).doesNotContain("sk-or-segredo").doesNotContain("segredo-do-turnstile").doesNotContain("senha-de-teste").doesNotContain("CNRY-");
    }

    @Test
    void todo_dado_e_escapado_no_html() throws Exception {
        String cookie = sessionCookie();

        String chunks = get("/admin/trechos?kind=repo_readme", cookie).body();
        assertThat(chunks).doesNotContain("<script>").contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;");

        String search = get("/admin/busca?q=" + URLEncoder.encode("<img src=x onerror=alert(1)>", StandardCharsets.UTF_8), cookie).body();
        assertThat(search).doesNotContain("<img src=x").contains("&lt;img");

        String repo = get("/admin/trechos?repo=" + URLEncoder.encode("\"><script>1</script>", StandardCharsets.UTF_8), cookie).body();
        assertThat(repo).doesNotContain("\"><script>");
    }

    @Test
    void busca_mostra_os_trechos_que_o_rag_mandaria_ao_modelo() throws Exception {
        var response = get("/admin/busca?q=" + URLEncoder.encode("RabbitMQ", StandardCharsets.UTF_8) + "&lang=pt", sessionCookie());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Testar a busca").contains("só BM25").contains("RabbitMQ").contains("pontuação RRF");
    }

    @Test
    void trechos_filtram_por_tipo_e_paginam() throws Exception {
        String cookie = sessionCookie();

        String projects = get("/admin/trechos?kind=project&lang=pt", cookie).body();
        assertThat(projects).contains("Projeto apiSuporte").doesNotContain("README of apiSuporte");
        assertThat(get("/admin/trechos?page=999", cookie).statusCode()).isEqualTo(200);
        assertThat(get("/admin/trechos?page=-5&kind=" + "x".repeat(500), cookie).statusCode()).isEqualTo(200);
    }

    @Test
    void sair_apaga_o_cookie() throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/logout")).header("Cookie", sessionCookie())
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("Set-Cookie").orElse("")).contains("Max-Age=0");
    }
}
