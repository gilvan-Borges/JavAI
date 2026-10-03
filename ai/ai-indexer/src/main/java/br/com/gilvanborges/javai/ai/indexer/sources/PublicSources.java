package br.com.gilvanborges.javai.ai.indexer.sources;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.source.HtmlText;
import br.com.gilvanborges.javai.ai.core.source.PublicTextChunker;
import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lê as fontes públicas configuradas em {@link SourcesProperties}: documentos do site, PDFs, perfil do GitHub e os
 * links. Cada fonte vira trechos literais; uma que falhar vira aviso no relatório e não derruba as outras.
 */
@Component
@EnableConfigurationProperties(SourcesProperties.class)
public class PublicSources {

    private static final Logger LOG = LoggerFactory.getLogger(PublicSources.class);
    /** Prefixo da chave, no registro da versão, que guarda o hash do que foi lido de cada fonte. */
    public static final String HASH_KEY = "src:";

    /**
     * @param chunks      os trechos de todas as fontes que deram certo
     * @param hashes      chave {@code src:<id>} → SHA-256 do texto lido, para o próximo ciclo saber o que mudou
     * @param status      uma linha por fonte, para o painel: tipo, endereço, ok ou erro, trechos
     * @param repos       repositórios descobertos nos usuários do GitHub (só com a descoberta ligada)
     */
    public record Read(List<Chunk> chunks, Map<String, String> hashes, List<Map<String, Object>> status, List<String> repos) {
    }

    private final SourcesProperties properties;
    private final IndexerProperties indexer;
    private final JsonMapper json;
    private final HttpText http = new HttpText();

    public PublicSources(SourcesProperties properties, IndexerProperties indexer, JsonMapper json) {
        this.properties = properties;
        this.indexer = indexer;
        this.json = json;
    }

    /** O link que aparece como fonte do trecho. É a própria URL lida; os testes trocam o servidor local por um endereço https. */
    String shownUrl(String url) {
        return url;
    }

    public SourcesProperties properties() {
        return properties;
    }

    public Read read() {
        var chunks = new ArrayList<Chunk>();
        var hashes = new LinkedHashMap<String, String>();
        var status = new ArrayList<Map<String, Object>>();
        var repos = new LinkedHashSet<String>();

        for (String url : properties.siteDocuments()) {
            document(SourceKind.SITE, "Site", url, chunks, hashes, status);
        }
        for (String url : properties.documents()) {
            document(SourceKind.DOCUMENT, "Documento", url, chunks, hashes, status);
        }
        for (String user : properties.githubUsers()) {
            githubUser(user, chunks, hashes, status, repos);
        }
        link(SourceKind.SITE, "Site", properties.siteUrl(), "Public website of the owner: ", chunks, status);
        link(SourceKind.DOCUMENT, "LinkedIn", properties.linkedinUrl(),
                "LinkedIn profile of the owner (cannot be read automatically, only linked): ", chunks, status);
        return new Read(List.copyOf(chunks), hashes, status, List.copyOf(repos));
    }

    private void document(SourceKind kind, String label, String url, List<Chunk> chunks, Map<String, String> hashes,
                          List<Map<String, Object>> status) {
        try {
            var body = http.get(url, Map.of());
            String text;
            if (PdfText.isPdf(body.bytes())) {
                text = PdfText.extract(body.bytes());
            } else {
                text = body.text();
                if (HtmlText.looksLikeHtml(text)) {
                    text = HtmlText.toText(text);
                }
            }
            text = ContactRedactor.redact(text);
            var made = PublicTextChunker.chunk(kind, label + " " + hostOf(url), shownUrl(url), shownUrl(url), text);
            chunks.addAll(made);
            hashes.put(HASH_KEY + url, sha256(text));
            status.add(row(kind, url, "ok", made.size(), null));
            if (made.isEmpty()) {
                status.set(status.size() - 1, row(kind, url, "vazio", 0, "a fonte não tem texto (site que só monta o conteúdo no navegador?)"));
            }
        } catch (Exception e) {
            interrupted(e);
            LOG.warn("Fonte {} falhou: {}", url, e.getClass().getSimpleName());
            status.add(row(kind, url, "erro", 0, describe(e)));
        }
    }

    private void githubUser(String user, List<Chunk> chunks, Map<String, String> hashes, List<Map<String, Object>> status,
                            Set<String> repos) {
        String page = "https://github.com/" + user;
        try {
            JsonNode profile = json.readTree(http.get(indexer.githubApiBase() + "/users/" + user, Map.of("Accept", "application/vnd.github+json")).text());
            var text = new StringBuilder("## Perfil no GitHub\n\n");
            appendField(text, "Nome", profile.path("name"));
            appendField(text, "Bio", profile.path("bio"));
            appendField(text, "Empresa", profile.path("company"));
            appendField(text, "Local", profile.path("location"));
            appendField(text, "Site", profile.path("blog"));
            text.append("Repositórios públicos: ").append(profile.path("public_repos").asInt()).append("\n\n");

            JsonNode list = json.readTree(http.get(indexer.githubApiBase() + "/users/" + user + "/repos?per_page=100&sort=pushed",
                    Map.of("Accept", "application/vnd.github+json")).text());
            text.append("## Repositórios públicos\n\n");
            for (JsonNode repo : list) {
                String full = repo.path("full_name").asString("");
                if (full.isEmpty() || properties.excluded(full)) {
                    continue;
                }
                text.append("- ").append(repo.path("name").asString()).append(": ")
                        .append(repo.path("description").asString("sem descrição"));
                if (!repo.path("language").isNull() && !repo.path("language").asString("").isEmpty()) {
                    text.append(" (").append(repo.path("language").asString()).append(")");
                }
                text.append("\n");
                if (!repo.path("fork").asBoolean() && !repo.path("archived").asBoolean() && repos.size() < properties.githubMaxRepos()) {
                    repos.add(full);
                }
            }
            try {
                var readme = http.get(indexer.githubApiBase() + "/repos/" + user + "/" + user + "/readme",
                        Map.of("Accept", "application/vnd.github.raw+json"));
                text.append("\n## README do perfil\n\n").append(readme.text());
            } catch (java.io.IOException noProfileReadme) {
                // Nem todo usuário tem o repositório de perfil: não é erro.
            }
            String all = ContactRedactor.redact(text.toString());
            var made = PublicTextChunker.chunk(SourceKind.GITHUB_PROFILE, "GitHub " + user, "github:" + user, page, all);
            chunks.addAll(made);
            hashes.put(HASH_KEY + "github:" + user, sha256(all));
            status.add(row(SourceKind.GITHUB_PROFILE, page, "ok", made.size(), null));
        } catch (Exception e) {
            interrupted(e);
            LOG.warn("GitHub {} falhou: {}", user, e.getClass().getSimpleName());
            status.add(row(SourceKind.GITHUB_PROFILE, page, "erro", 0, describe(e)));
        }
    }

    private void link(SourceKind kind, String label, String url, String prefix, List<Chunk> chunks, List<Map<String, Object>> status) {
        if (url.isEmpty()) {
            return;
        }
        try {
            var made = PublicTextChunker.chunk(kind, label, "link:" + url, url, prefix + url);
            chunks.addAll(made);
            status.add(row(kind, url, "só link", made.size(), null));
        } catch (RuntimeException e) {
            status.add(row(kind, url, "erro", 0, describe(e)));
        }
    }

    /** Repositórios a ler: os extras da configuração mais os descobertos, sem os excluídos e sem repetir. */
    public List<String> repositories(List<String> discovered) {
        var all = new LinkedHashSet<String>(properties.githubRepos());
        if (properties.githubDiscover()) {
            all.addAll(discovered);
        }
        all.removeIf(properties::excluded);
        return List.copyOf(all);
    }

    private static void appendField(StringBuilder out, String label, JsonNode value) {
        if (!value.isNull() && !value.asString("").isBlank()) {
            out.append(label).append(": ").append(value.asString()).append("\n");
        }
    }

    private static Map<String, Object> row(SourceKind kind, String url, String state, int chunks, String note) {
        var row = new LinkedHashMap<String, Object>();
        row.put("tipo", kind.db());
        row.put("fonte", url);
        row.put("estado", state);
        row.put("trechos", chunks);
        if (note != null) {
            row.put("aviso", note);
        }
        return row;
    }

    private static String hostOf(String url) {
        String host = java.net.URI.create(url).getHost();
        return host == null ? url : host;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void interrupted(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\s+", " ");
        String text = e.getClass().getSimpleName() + message;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
