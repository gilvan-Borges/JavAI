package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.ApiFixtures;
import br.com.gilvanborges.javai.ai.api.index.IndexRepository;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Sem usuário e senha no ambiente, ou com senha curta, o /admin simplesmente não existe. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiapi.db.enabled=false",
        "aiapi.model-dir=/nao-existe",
        "aiapi.admin.user=gilvan",
        "aiapi.admin.password=curta",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
        "management.server.port=0"})
@Import(AdminDisabledTest.Stub.class)
class AdminDisabledTest {

    @TestConfiguration
    static class Stub {
        @Bean
        IndexRepository indexRepository() {
            return ApiFixtures.repository(ApiFixtures.index());
        }

        @Bean
        UsageStore usageStore() {
            return new ApiFixtures.MemoryStore();
        }
    }

    @LocalServerPort
    int port;

    @Test
    void senha_curta_desliga_o_admin_e_a_rota_responde_404() throws Exception {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for (String path : new String[]{"/admin", "/admin/login", "/admin/trechos", "/admin/busca"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(404);
        }
        var post = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=gilvan&password=curta")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(404);
    }
}
