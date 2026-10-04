package br.com.gilvanborges.javai.ai.indexer.repo;

import br.com.gilvanborges.javai.ai.core.repo.RepoFileFilter;
import br.com.gilvanborges.javai.ai.core.repo.SecretScanner;
import br.com.gilvanborges.javai.ai.indexer.IndexerProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lê um repositório público do GitHub. Antes de clonar, pergunta à API se ele continua público, não é fork e cabe
 * no limite de tamanho: a IA não confia cegamente no CRUD, porque o dono pode ter mudado o repositório depois de
 * ligá-lo (spec 01, "Indexador"). O clone é raso (último commit), sem credencial, numa pasta temporária que é
 * apagada no fim, e só os arquivos aprovados pelo {@link RepoFileFilter} e limpos pelo {@link SecretScanner} saem daqui.
 */
@Component
public class GitHubRepoSource implements RepoSource {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubRepoSource.class);
    private static final Pattern FULL_NAME = Pattern.compile("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}$");
    private static final int MAX_FILES = 600;
    /** Quantos commits entram no dossiê. O clone raso passa a ter essa profundidade, então não há chamada extra à API do GitHub. */
    static final int COMMIT_HISTORY = 10;
    private static final int MAX_COMMIT_MESSAGE = 100;

    private final IndexerProperties properties;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonMapper json = JsonMapper.builder().build();
    /** ETag e commit já vistos por repositório: com {@code If-None-Match} o GitHub responde 304, que não gasta o limite da API. */
    private final Map<String, String[]> heads = new ConcurrentHashMap<>();

    public GitHubRepoSource(IndexerProperties properties) {
        this.properties = properties;
    }

    @Override
    public RepoRead read(String fullName) {
        if (!FULL_NAME.matcher(fullName).matches()) {
            return RepoRead.skipped(fullName, "nome de repositório inválido");
        }
        try {
            JsonNode meta = metadata(fullName);
            if (meta == null) {
                return RepoRead.skipped(fullName, "repositório não encontrado (ou privado)");
            }
            if (meta.path("private").asBoolean(true)) {
                return RepoRead.skipped(fullName, "repositório privado");
            }
            if (meta.path("fork").asBoolean(true)) {
                return RepoRead.skipped(fullName, "repositório é fork");
            }
            long sizeKb = meta.path("size").asLong(Long.MAX_VALUE);
            if (sizeKb > properties.maxRepoSizeKb()) {
                return RepoRead.skipped(fullName, "repositório grande demais (" + sizeKb + " KB; limite "
                        + properties.maxRepoSizeKb() + " KB)");
            }
            return clone(fullName, meta.path("default_branch").asString("main"), meta);
        } catch (IOException | GitAPIException e) {
            LOG.warn("Falha ao ler {}: {}", fullName, e.getClass().getSimpleName());
            return RepoRead.skipped(fullName, "falha ao ler o repositório (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RepoRead.skipped(fullName, "leitura interrompida");
        } catch (RuntimeException e) {
            // Um repositório com formato inesperado vira "pulado" só para ele: o índice dos demais segue.
            LOG.warn("Erro inesperado ao ler {}: {}", fullName, e.getClass().getSimpleName());
            return RepoRead.skipped(fullName, "erro inesperado (" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public Optional<String> head(String fullName) {
        if (!FULL_NAME.matcher(fullName).matches()) {
            return Optional.empty();
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(
                            properties.githubApiBase().replaceAll("/+$", "") + "/repos/" + fullName + "/commits/HEAD"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/vnd.github.sha")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            String[] known = heads.get(fullName);
            if (known != null) {
                request.header("If-None-Match", known[0]);
            }
            HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 304 && known != null) {
                return Optional.of(known[1]);
            }
            String sha = response.body() == null ? "" : response.body().strip();
            if (response.statusCode() != 200 || !sha.matches("[0-9a-f]{40}")) {
                return Optional.empty();
            }
            response.headers().firstValue("ETag").ifPresent(etag -> heads.put(fullName, new String[]{etag, sha}));
            return Optional.of(sha);
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** @return os metadados, ou {@code null} se o GitHub respondeu 404 (não existe ou é privado, sem credencial) */
    private JsonNode metadata(String fullName) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(properties.githubApiBase().replaceAll("/+$", "") + "/repos/" + fullName))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("A API do GitHub respondeu HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }

    private RepoRead clone(String fullName, String branch, JsonNode meta) throws IOException, GitAPIException {
        Path dir = Files.createTempDirectory("javai-repo-");
        try {
            String url = properties.githubCloneBase() + fullName + ".git";
            try (Git git = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(dir.toFile())
                    .setDepth(COMMIT_HISTORY)
                    .setBranch("refs/heads/" + branch)
                    .setCloneSubmodules(false)
                    .setTimeout(120)
                    .call()) {
                var headId = git.getRepository().resolve("HEAD");
                if (headId == null) {
                    // Repositório público recém-criado e ainda sem commit: não há o que ler, e não pode derrubar os outros.
                    return RepoRead.skipped(fullName, "repositório vazio (sem commits)");
                }
                String sha = headId.getName();
                var commits = new ArrayList<org.eclipse.jgit.revwalk.RevCommit>();
                git.log().setMaxCount(COMMIT_HISTORY).call().forEach(commits::add);
                String facts = facts(fullName, meta, commits, true);
                if (SecretScanner.firstFinding(facts).isPresent()) {
                    // Uma mensagem de commit com algo parecido com segredo não derruba o dossiê: ele sai só com datas e hashes.
                    facts = facts(fullName, meta, commits, false);
                }
                return collect(fullName, sha, dir, facts);
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private RepoRead collect(String fullName, String sha, Path root, String facts) throws IOException {
        var files = new ArrayList<RepoFile>();
        var notes = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return dir.getFileName() != null && dir.getFileName().toString().equals(".git")
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Link simbólico poderia apontar para fora da pasta clonada (por exemplo /etc): nunca é seguido.
                if (attrs.isSymbolicLink() || !attrs.isRegularFile() || files.size() >= MAX_FILES) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!RepoFileFilter.allowed(relative, attrs.size())) {
                    return FileVisitResult.CONTINUE;
                }
                String text = readText(file);
                if (text == null) {
                    notes.add("arquivo " + relative + " pulado: não é texto UTF-8");
                } else if (SecretScanner.firstFinding(text).isPresent()) {
                    // Só o caminho e o nome da regra vão para o relatório; o valor encontrado nunca sai daqui.
                    notes.add("arquivo " + relative + " pulado: possível segredo (" + SecretScanner.firstFinding(text).get() + ")");
                } else {
                    files.add(new RepoFile(relative, text));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // O clone é raso e o README não diz quando o projeto mudou: sem este resumo a IA não tem como responder
        // "quando foi o último commit?". Passa pelo mesmo SecretScanner dos demais arquivos.
        if (SecretScanner.firstFinding(facts).isEmpty()) {
            files.add(new RepoFile(RepoActivity.FACTS_FILE, facts));
        }
        files.sort(Comparator.comparing(RepoFile::path));
        return new RepoRead(fullName, sha, files, null, notes);
    }

    /**
     * O dossiê do repositório em texto, nos dois idiomas: datas, linguagem, tópicos, o último commit (com as etiquetas que o
     * {@link RepoActivity} lê) e os commits recentes. Tudo vem do clone e da chamada de metadados que já existiam.
     *
     * @param withMessages falso para omitir as mensagens dos commits (quando uma delas parece segredo)
     */
    static String facts(String fullName, JsonNode meta, List<org.eclipse.jgit.revwalk.RevCommit> commits, boolean withMessages) {
        var head = commits.get(0);
        var sb = new StringBuilder();
        sb.append("# Informações do repositório / Repository facts\n\n");
        sb.append("Repositório / Repository: ").append(fullName).append("\n");
        sb.append(RepoActivity.COMMIT_LABEL).append(utcDate(head)).append(" (UTC), commit ")
                .append(head.getName(), 0, 7).append("\n");
        if (withMessages) {
            sb.append(RepoActivity.MESSAGE_LABEL).append(commitMessage(head)).append("\n");
        }
        appendField(sb, "Último push / Last push", meta.path("pushed_at").asString(""));
        appendField(sb, "Criado em / Created", meta.path("created_at").asString(""));
        appendField(sb, "Branch principal / Default branch", meta.path("default_branch").asString(""));
        appendField(sb, "Linguagem principal / Main language", meta.path("language").asString(""));
        appendField(sb, "Descrição / Description", meta.path("description").asString(""));
        var topics = new ArrayList<String>();
        for (JsonNode topic : meta.path("topics")) {
            topics.add(topic.asString(""));
        }
        appendField(sb, "Tópicos / Topics", String.join(", ", topics.stream().filter(s -> !s.isBlank()).toList()));
        appendField(sb, "Licença / License", meta.path("license").path("spdx_id").asString(""));
        appendField(sb, "Estrelas / Stars", meta.path("stargazers_count").asString(""));
        sb.append("\n## Commits recentes / Recent commits\n");
        for (var commit : commits) {
            sb.append("- ").append(utcDate(commit)).append(" · ").append(commit.getName(), 0, 7);
            if (withMessages) {
                sb.append(" · ").append(commitMessage(commit));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String utcDate(org.eclipse.jgit.revwalk.RevCommit commit) {
        return commit.getCommitterIdent().getWhenAsInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    /** Primeira linha da mensagem, sem caracteres de controle e com tamanho limitado (o e-mail do autor nunca entra). */
    private static String commitMessage(org.eclipse.jgit.revwalk.RevCommit commit) {
        String text = commit.getShortMessage().replaceAll("\\p{Cntrl}", " ").strip();
        return text.length() > MAX_COMMIT_MESSAGE ? text.substring(0, MAX_COMMIT_MESSAGE).strip() + "…" : text;
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (!value.isBlank()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    // NOFOLLOW: se for um link, apaga o link e nunca o destino.
                    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                        p.toFile().setWritable(true);
                        Files.delete(p);
                    }
                } catch (IOException ignored) {
                    // melhor esforço: a pasta é temporária
                }
            });
        } catch (IOException ignored) {
            // melhor esforço
        }
    }
}
