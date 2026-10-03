package br.com.gilvanborges.javai.ai.indexer.repo;

import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A conferência barata de commit novo: um GET condicional no HEAD, que o GitHub responde com 304 quando nada mudou. */
class GitHubHeadTest {

    private static final String SHA_A = "a".repeat(40);
    private static final String SHA_B = "b".repeat(40);

    private HttpServer api;
    private String sha = SHA_A;
    private String etag = "\"v1\"";
    private int status = 200;
    private final List<String> conditional = new ArrayList<>();
    private final List<String> accepts = new ArrayList<>();

    @BeforeEach
    void sobe() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/repos/dono/projeto/commits/HEAD", exchange -> {
            accepts.add(exchange.getRequestHeaders().getFirst("Accept"));
            String sent = exchange.getRequestHeaders().getFirst("If-None-Match");
            conditional.add(sent == null ? "-" : sent);
            if (status == 200 && etag.equals(sent)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                byte[] body = sha.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        api.start();
    }

    @AfterEach
    void desce() {
        api.stop(0);
    }

    private GitHubRepoSource source() {
        return new GitHubRepoSource(new IndexerProperties("http://127.0.0.1:1", Duration.ofMinutes(5), 51_200, 300, "/models", 1,
                "http://127.0.0.1:" + api.getAddress().getPort(), "http://127.0.0.1:1/", Duration.ZERO));
    }

    @Test
    void devolve_o_commit_e_nas_vezes_seguintes_usa_o_etag_para_nao_gastar_o_limite() {
        var source = source();

        assertThat(source.head("dono/projeto")).contains(SHA_A);
        assertThat(source.head("dono/projeto")).contains(SHA_A);

        assertThat(conditional).containsExactly("-", "\"v1\"");
        assertThat(accepts).allMatch("application/vnd.github.sha"::equals);
    }

    @Test
    void commit_novo_aparece_quando_o_etag_muda() {
        var source = source();
        source.head("dono/projeto");
        sha = SHA_B;
        etag = "\"v2\"";

        assertThat(source.head("dono/projeto")).contains(SHA_B);
    }

    @Test
    void erro_da_api_nome_invalido_ou_resposta_estranha_dao_vazio() {
        var source = source();

        status = 403; // limite da API
        assertThat(source.head("dono/projeto")).isEmpty();
        status = 200;
        sha = "<html>nao e um sha</html>";
        assertThat(source.head("dono/projeto")).isEmpty();
        assertThat(source.head("../etc/passwd")).isEmpty();
        assertThat(source.head("dono/inexistente")).isEmpty();
    }
}
