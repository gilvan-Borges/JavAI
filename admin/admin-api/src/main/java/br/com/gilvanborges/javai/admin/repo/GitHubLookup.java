package br.com.gilvanborges.javai.admin.repo;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Consulta os metadados públicos de um repositório na API do GitHub na hora de ligá-lo: fork, branch padrão e
 * tamanho. Sem token: só o que é público. Repositório privado ou inexistente responde 404 e a ligação é recusada —
 * é a mesma regra que o CHECK do banco e o ai-indexer aplicam de novo depois (defesa em camadas).
 */
@Component
class GitHubLookup {

    /** O que o GitHub diz do repositório público. */
    record Meta(String fullName, boolean fork, String defaultBranch, int sizeKb) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Vazio quando o repositório não existe ou é privado (para quem não tem token, é a mesma coisa). */
    Optional<Meta> fetch(String fullName) {
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + fullName))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "JavAI-admin/1.0")
                .GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode node = JSON.readTree(response.body());
            if (node.path("private").asBoolean()) {
                return Optional.empty();
            }
            return Optional.of(new Meta(
                    node.path("full_name").asString(fullName),
                    node.path("fork").asBoolean(),
                    node.path("default_branch").asString("main"),
                    node.path("size").asInt(0)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
