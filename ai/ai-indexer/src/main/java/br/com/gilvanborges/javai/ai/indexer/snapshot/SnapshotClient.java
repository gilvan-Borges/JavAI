package br.com.gilvanborges.javai.ai.indexer.snapshot;

import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;
import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Lê o snapshot público do CRUD, o único ponto de contato entre os dois lados (spec 01). Usa GET condicional: se o
 * {@code ETag} não mudou, o Caddy responde 304 e nada é refeito. A IA lê este arquivo como qualquer visitante.
 */
@Component
public class SnapshotClient {

    /** O snapshot tem poucos KB; um limite generoso impede que um servidor errado nos entregue gigabytes. */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    /** Resultado de uma leitura: {@code data} vem vazio quando o servidor respondeu 304 (nada mudou). */
    public record Fetch(String etag, Optional<SnapshotData> data) {
        public boolean notModified() {
            return data.isEmpty();
        }
    }

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    // O snapshot pode ganhar campos novos no CRUD antes de a IA conhecê-los: campo desconhecido não pode derrubar a leitura.
    private final JsonMapper json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public SnapshotClient(IndexerProperties properties) {
        this.baseUrl = properties.snapshotBaseUrl().replaceAll("/+$", "");
        URI uri = URI.create(baseUrl);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !loopback) {
            throw new IllegalArgumentException("A URL do snapshot precisa ser https (http só em localhost, para testes).");
        }
    }

    /** @param previousEtag o ETag da leitura anterior, ou {@code null} para ler sempre */
    public Fetch fetch(String lang, String previousEtag) throws IOException, InterruptedException {
        if (!lang.equals("pt") && !lang.equals("en")) {
            throw new IllegalArgumentException("lang precisa ser pt ou en.");
        }
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/snapshot/" + lang + ".json"))
                .timeout(Duration.ofSeconds(30)).GET().header("Accept", "application/json");
        if (previousEtag != null) {
            request.header("If-None-Match", previousEtag);
        }
        HttpResponse<java.io.InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() == 304) {
                return new Fetch(previousEtag, Optional.empty());
            }
            if (response.statusCode() != 200) {
                throw new IOException("O snapshot respondeu HTTP " + response.statusCode() + " para " + lang + ".");
            }
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("O snapshot passou de " + MAX_BYTES + " bytes e foi recusado.");
            }
            SnapshotData data = json.readValue(bytes, SnapshotData.class);
            if (data.profile() == null || !lang.equals(data.lang())) {
                throw new IOException("O snapshot de " + lang + " veio incompleto ou no idioma errado.");
            }
            return new Fetch(response.headers().firstValue("etag").orElse(null), Optional.of(data));
        }
    }
}
