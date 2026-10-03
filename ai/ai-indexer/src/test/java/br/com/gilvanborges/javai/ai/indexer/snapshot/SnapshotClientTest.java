package br.com.gilvanborges.javai.ai.indexer.snapshot;

import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SnapshotClientTest {

    private static final String PT = """
            {"version":3,"generatedAt":"2026-10-03T06:00:00Z","lang":"pt",
             "profile":{"fullName":"Gilvan Borges","headline":"Engenheiro","summary":"Resumo.","location":"Rio","links":[{"label":"GitHub","url":"https://github.com/gilvan-Borges"}],"availability":null},
             "experiences":[{"company":"Vibetex","role":"Eng","startedOn":"2025-01-01","endedOn":null,"description":"d","highlights":["h"],"stack":["Java"]}],
             "projects":[],"skills":[{"name":"Java","category":"linguagem","declared":true,"notes":null}],
             "education":[],"certifications":[],"faq":[],
             "campoNovoQueAIaindaNaoConhece":{"x":1}}""";

    private HttpServer server;
    private final AtomicReference<String> seenIfNoneMatch = new AtomicReference<>();
    private int status = 200;
    private String body = PT;
    private String etag = "\"abc\"";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/snapshot/", exchange -> {
            seenIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (etag != null) {
                exchange.getResponseHeaders().add("ETag", etag);
            }
            exchange.sendResponseHeaders(status, status == 304 ? -1 : bytes.length);
            if (status != 304) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void le_o_snapshot_e_ignora_campos_que_ainda_nao_conhece() throws Exception {
        var fetch = client().fetch("pt", null);

        assertThat(fetch.notModified()).isFalse();
        assertThat(fetch.etag()).isEqualTo("\"abc\"");
        var data = fetch.data().orElseThrow();
        assertThat(data.profile().fullName()).isEqualTo("Gilvan Borges");
        assertThat(data.experiences()).singleElement().satisfies(e -> assertThat(e.company()).isEqualTo("Vibetex"));
        assertThat(data.repositories()).isEmpty(); // snapshot antigo, sem "repositories": vira lista vazia
        assertThat(seenIfNoneMatch.get()).isNull();
    }

    @Test
    void manda_if_none_match_e_trata_304_como_nada_mudou() throws Exception {
        status = 304;

        var fetch = client().fetch("pt", "\"abc\"");

        assertThat(seenIfNoneMatch.get()).isEqualTo("\"abc\"");
        assertThat(fetch.notModified()).isTrue();
        assertThat(fetch.etag()).isEqualTo("\"abc\"");
    }

    @Test
    void resposta_que_nao_e_200_nem_304_e_erro() {
        status = 500;

        assertThatIOException().isThrownBy(() -> client().fetch("pt", null)).withMessageContaining("HTTP 500");
    }

    @Test
    void snapshot_no_idioma_errado_ou_sem_perfil_e_recusado() {
        body = PT.replace("\"lang\":\"pt\"", "\"lang\":\"en\"");
        assertThatIOException().isThrownBy(() -> client().fetch("pt", null)).withMessageContaining("idioma errado");

        body = PT.replace("\"profile\":", "\"semPerfil\":");
        assertThatIOException().isThrownBy(() -> client().fetch("pt", null));
    }

    @Test
    void json_invalido_e_erro() {
        body = "isto nao e json";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client().fetch("pt", null)).isNotNull();
    }

    @Test
    void resposta_gigante_e_recusada() {
        body = "{\"lang\":\"pt\",\"x\":\"" + "a".repeat(SnapshotClient.MAX_BYTES + 10) + "\"}";

        assertThatIOException().isThrownBy(() -> client().fetch("pt", null)).withMessageContaining("passou de");
    }

    @Test
    void so_aceita_pt_e_en_e_so_https_fora_do_localhost() {
        assertThatIllegalArgumentException().isThrownBy(() -> client().fetch("es", null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SnapshotClient(properties("http://exemplo.com")));
        assertThatIllegalArgumentException().isThrownBy(() -> new SnapshotClient(properties("ftp://exemplo.com")));
        new SnapshotClient(properties("https://conteudo.gilvanborges.com.br")); // não lança
    }

    private SnapshotClient client() {
        return new SnapshotClient(properties("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static IndexerProperties properties(String baseUrl) {
        return new IndexerProperties(baseUrl, Duration.ofMinutes(5), 51_200, 300, "/models", 1,
                "https://api.github.com", "https://github.com/", java.time.Duration.ZERO);
    }
}
