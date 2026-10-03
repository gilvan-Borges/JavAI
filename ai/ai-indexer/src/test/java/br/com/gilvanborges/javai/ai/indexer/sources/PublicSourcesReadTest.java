package br.com.gilvanborges.javai.ai.indexer.sources;

import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicSourcesReadTest {

    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void sobe() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/llms-full.txt", ex -> send(ex, 200, "# Gilvan\n\nEngenheiro de software com foco em Java e arquitetura.\n\n## Skills\n\nJava, Spring Boot e PostgreSQL."));
        server.createContext("/users/gilvan", ex -> send(ex, 200, "{\"name\":\"Gilvan\",\"bio\":\"Dev Java\",\"public_repos\":3}"));
        server.createContext("/users/gilvan/repos", ex -> send(ex, 200, "[{\"name\":\"a\",\"full_name\":\"gilvan/a\",\"description\":\"Projeto A\",\"language\":\"Java\",\"fork\":false,\"archived\":false},"
                + "{\"name\":\"estudo\",\"full_name\":\"gilvan/estudo\",\"description\":\"x\",\"fork\":false,\"archived\":false},"
                + "{\"name\":\"f\",\"full_name\":\"gilvan/f\",\"description\":\"fork\",\"fork\":true,\"archived\":false}]"));
        server.createContext("/repos/gilvan/gilvan/readme", ex -> send(ex, 404, "{}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void desce() {
        server.stop(0);
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private PublicSources sources(SourcesProperties p) {
        var indexer = new IndexerProperties("http://127.0.0.1:1", Duration.ofMinutes(5), 51_200, 300, "/nada", 1, base,
                "https://github.com/", Duration.ZERO);
        return new PublicSources(p, indexer, JsonMapper.builder().build()) {
            @Override
            String shownUrl(String url) {
                return url.replace(base, "https://fonte.example");
            }
        };
    }

    @Test
    void le_documento_perfil_do_github_e_links_e_uma_fonte_quebrada_nao_derruba_as_outras() {
        var props = new SourcesProperties(List.of(base + "/llms-full.txt", base + "/nao-existe"), List.of(), List.of("gilvan"),
                List.of("gilvan/extra"), true, List.of("estudo"), 30, "https://www.linkedin.com/in/x/", "https://x.example/", Duration.ofMinutes(30));

        var read = sources(props).read();

        assertThat(read.chunks()).extracting(c -> c.kind()).contains(SourceKind.SITE, SourceKind.GITHUB_PROFILE, SourceKind.DOCUMENT);
        assertThat(read.status()).extracting(r -> r.get("estado")).contains("ok", "erro", "só link");
        assertThat(read.hashes()).containsKeys(PublicSources.HASH_KEY + base + "/llms-full.txt", PublicSources.HASH_KEY + "github:gilvan");
        // Descoberta: sem fork e sem o excluído; mais o extra configurado.
        assertThat(sources(props).repositories(read.repos())).containsExactlyInAnyOrder("gilvan/extra", "gilvan/a");
        assertThat(read.chunks().stream().filter(c -> c.kind() == SourceKind.GITHUB_PROFILE).map(c -> c.body()).reduce("", String::concat))
                .contains("Projeto A").doesNotContain("estudo");
    }

    @Test
    void sem_descoberta_so_entram_os_repositorios_configurados() {
        var props = new SourcesProperties(null, null, List.of("gilvan"), List.of("gilvan/extra"), false, null, 30, "", "", Duration.ofMinutes(30));
        var src = sources(props);

        assertThat(src.repositories(src.read().repos())).containsExactly("gilvan/extra");
    }

    @Test
    void mesmo_texto_gera_o_mesmo_hash() {
        var props = new SourcesProperties(List.of(base + "/llms-full.txt"), null, null, null, false, null, 30, "", "", Duration.ofMinutes(30));
        var src = sources(props);

        assertThat(src.read().hashes()).isEqualTo(src.read().hashes());
    }
}
