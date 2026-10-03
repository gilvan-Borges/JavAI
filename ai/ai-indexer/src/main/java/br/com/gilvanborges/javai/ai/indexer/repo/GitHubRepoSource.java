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

    private final IndexerProperties properties;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final JsonMapper json = JsonMapper.builder().build();

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
            return clone(fullName, meta.path("default_branch").asString("main"));
        } catch (IOException | GitAPIException e) {
            LOG.warn("Falha ao ler {}: {}", fullName, e.getClass().getSimpleName());
            return RepoRead.skipped(fullName, "falha ao ler o repositório (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RepoRead.skipped(fullName, "leitura interrompida");
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

    private RepoRead clone(String fullName, String branch) throws IOException, GitAPIException {
        Path dir = Files.createTempDirectory("javai-repo-");
        try {
            String url = properties.githubCloneBase() + fullName + ".git";
            try (Git git = Git.cloneRepository()
                    .setURI(url)
                    .setDirectory(dir.toFile())
                    .setDepth(1)
                    .setBranch("refs/heads/" + branch)
                    .setCloneSubmodules(false)
                    .setTimeout(120)
                    .call()) {
                String sha = git.getRepository().resolve("HEAD").getName();
                return collect(fullName, sha, dir);
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private RepoRead collect(String fullName, String sha, Path root) throws IOException {
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
        files.sort(Comparator.comparing(RepoFile::path));
        return new RepoRead(fullName, sha, files, null, notes);
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
