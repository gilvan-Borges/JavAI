package br.com.gilvanborges.javai.ai.indexer.sources;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * GET de uma fonte pública, com os limites de quem lê coisa da internet: só https (http só em localhost, nos testes),
 * redirecionamento que nunca desce de https para http, tamanho máximo e prazo. As URLs vêm da configuração do dono,
 * nunca de um visitante.
 */
final class HttpText {

    static final int MAX_BYTES = 5 * 1024 * 1024;

    record Body(byte[] bytes, String contentType) {
        String text() {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    Body get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !loopback) {
            throw new IOException("A fonte precisa ser https.");
        }
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40)).GET().header("User-Agent", "JavAI-indexer/1.0");
        headers.forEach(request::header);
        HttpResponse<java.io.InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("A fonte passa de " + MAX_BYTES / 1024 / 1024 + " MB.");
            }
            return new Body(bytes, response.headers().firstValue("Content-Type").orElse(""));
        }
    }
}
