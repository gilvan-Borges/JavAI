package br.com.gilvanborges.javai.ai.api.admin;

import org.springframework.beans.factory.annotation.Value;
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
 * Fala com a porta interna do ai-indexer (mesma rede do Docker) para o painel /admin: pedir uma reindexação e ler o
 * estado das fontes e das últimas execuções. O endereço e o token vêm da configuração, nunca de quem usa o painel,
 * e a chamada não leva parâmetro nenhum: o painel só consegue mandar reler o que já está configurado.
 */
@Component
public class IndexerClient {

    public enum Result { STARTED, ALREADY_RUNNING, UNAVAILABLE }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json;
    private final String base;
    private final String token;

    public IndexerClient(JsonMapper json,
                         @Value("${aiapi.admin.indexer-url:http://ai-indexer:8080}") String base,
                         @Value("${aiapi.admin.indexer-token:}") String token) {
        this.json = json;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token.strip();
    }

    public boolean configured() {
        return !token.isEmpty();
    }

    public Result reindex() {
        return post("/internal/reindex");
    }

    /** Apaga toda a base de conhecimento; {@code STARTED} quer dizer que apagou. */
    public Result wipe() {
        return post("/internal/wipe");
    }

    private Result post(String path) {
        if (!configured()) {
            return Result.UNAVAILABLE;
        }
        try {
            var response = http.send(request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            return switch (response.statusCode()) {
                case 200, 202 -> Result.STARTED;
                case 409 -> Result.ALREADY_RUNNING;
                default -> Result.UNAVAILABLE;
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.UNAVAILABLE;
        } catch (java.io.IOException | RuntimeException e) {
            return Result.UNAVAILABLE;
        }
    }

    /** Fontes configuradas, se há indexação rodando e as últimas execuções; vazio se o indexador não respondeu. */
    public Optional<JsonNode> status() {
        if (!configured()) {
            return Optional.empty();
        }
        try {
            var response = http.send(request("/internal/status").GET().build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(json.readTree(response.body())) : Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5)).header("X-Indexer-Token", token);
    }
}
