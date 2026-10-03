package br.com.gilvanborges.javai.ai.api.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Confere o token do Cloudflare Turnstile no servidor (siteverify). A Cloudflare já recusa token repetido (uso
 * único) e vencido; aqui se confere também o hostname, para um token de outro site não valer. Falha de rede conta
 * como recusa: sem prova de que é gente, o chat não responde.
 */
public class Turnstile {

    private static final Logger LOG = LoggerFactory.getLogger(Turnstile.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http;
    private final String url;
    private final String secret;
    private final List<String> hostnames;

    public Turnstile(HttpClient http, String url, String secret, List<String> hostnames) {
        this.http = http;
        this.url = url;
        this.secret = secret;
        this.hostnames = hostnames;
    }

    /** Verdadeiro só se a Cloudflare confirmar o token e o hostname for o do portfólio. */
    public boolean verify(String token, String ip) {
        try {
            String form = "secret=" + enc(secret) + "&response=" + enc(token) + (ip == null ? "" : "&remoteip=" + enc(ip));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return false;
            }
            JsonNode body = JSON.readTree(response.body());
            return body.path("success").asBoolean(false) && hostnames.contains(body.path("hostname").asString(""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOG.warn("Turnstile indisponível ({}); recusando.", e.getClass().getSimpleName());
            return false;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
