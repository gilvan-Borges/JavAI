package br.com.gilvanborges.javai.ai.indexer.build;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SnapshotChunker;
import br.com.gilvanborges.javai.ai.core.embedding.Embedder;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import br.com.gilvanborges.javai.ai.core.evidence.SkillDetectors;
import br.com.gilvanborges.javai.ai.core.repo.RepoChunker;
import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;
import br.com.gilvanborges.javai.ai.indexer.repo.RepoSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Monta o índice completo: trechos do snapshot (nos dois idiomas), trechos dos repositórios, evidências de
 * habilidade e, se o modelo estiver disponível, um embedding por trecho. Sem rede nem banco: dá para testar inteiro.
 */
public final class IndexBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(IndexBuilder.class);

    private final int maxChunksPerRepo;

    public IndexBuilder(int maxChunksPerRepo) {
        this.maxChunksPerRepo = maxChunksPerRepo;
    }

    /**
     * O que foi montado.
     *
     * @param embeddings um vetor por trecho na mesma ordem, ou {@code null} se o modelo não estava disponível
     *                   (a busca segue só com BM25)
     * @param report     contagens e motivos; nunca conteúdo de arquivo nem valor de segredo
     */
    public record Built(List<Chunk> chunks, List<float[]> embeddings, List<Evidence> evidence, Map<String, Object> report) {
    }

    public Built build(Map<String, SnapshotData> snapshots, List<RepoSource.RepoRead> repos, Optional<Embedder> embedder) {
        var chunks = new ArrayList<Chunk>();
        var evidence = new LinkedHashMap<String, Evidence>();
        var report = new LinkedHashMap<String, Object>();
        var skipped = new ArrayList<String>();
        var notes = new ArrayList<String>();

        int snapshotChunks = 0;
        for (var snapshot : snapshots.values()) {
            var fromSnapshot = SnapshotChunker.chunk(snapshot);
            chunks.addAll(fromSnapshot);
            snapshotChunks += fromSnapshot.size();
        }

        int repoChunks = 0;
        int repoFiles = 0;
        Set<String> readRepos = new LinkedHashSet<>();
        for (var repo : repos) {
            if (repo.wasSkipped()) {
                skipped.add(repo.fullName() + ": " + repo.skippedReason());
                continue;
            }
            readRepos.add(repo.fullName());
            notes.addAll(repo.notes());
            int forThisRepo = 0;
            for (var file : prioritized(repo.files())) {
                repoFiles++;
                for (var e : SkillDetectors.detect(repo.fullName(), repo.commitSha(), file.path(), file.content())) {
                    evidence.putIfAbsent(e.skill() + "|" + e.repository() + "|" + e.path(), e);
                }
                if (forThisRepo >= maxChunksPerRepo) {
                    continue; // o teto vale para os trechos; a evidência de habilidade continua sendo calculada
                }
                for (var chunk : RepoChunker.chunk(repo.fullName(), repo.commitSha(), file.path(), file.content())) {
                    if (forThisRepo++ < maxChunksPerRepo) {
                        chunks.add(chunk);
                        repoChunks++;
                    }
                }
            }
        }

        List<float[]> embeddings = embed(chunks, embedder, report);

        report.put("trechosDoSnapshot", snapshotChunks);
        report.put("trechosDeRepositorios", repoChunks);
        report.put("arquivosLidos", repoFiles);
        report.put("repositoriosLidos", List.copyOf(readRepos));
        report.put("repositoriosPulados", List.copyOf(skipped));
        report.put("avisos", List.copyOf(notes));
        report.put("evidencias", evidence.size());
        report.put("comEmbeddings", embeddings != null);
        return new Built(List.copyOf(chunks), embeddings, List.copyOf(evidence.values()), report);
    }

    /** README primeiro, depois manifestos, depois o resto: se o teto cortar, corta o que menos diz sobre o projeto. */
    private static List<RepoSource.RepoFile> prioritized(List<RepoSource.RepoFile> files) {
        return files.stream().sorted(Comparator.comparingInt((RepoSource.RepoFile f) -> rank(f.path())).thenComparing(RepoSource.RepoFile::path)).toList();
    }

    private static int rank(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        if (name.equals("readme.md")) {
            return 0;
        }
        if (name.endsWith(".md")) {
            return 1;
        }
        if (name.equals("pom.xml") || name.startsWith("build.gradle") || name.equals("package.json") || name.equals("pubspec.yaml")) {
            return 2;
        }
        return lower.contains("/test/") ? 4 : 3;
    }

    private static List<float[]> embed(List<Chunk> chunks, Optional<Embedder> embedder, Map<String, Object> report) {
        if (embedder.isEmpty()) {
            return null;
        }
        try {
            var vectors = new ArrayList<float[]>(chunks.size());
            for (Chunk chunk : chunks) {
                vectors.add(embedder.get().embedPassage(chunk.searchText()));
            }
            report.put("modeloDeEmbeddings", embedder.get().modelId());
            return vectors;
        } catch (RuntimeException e) {
            // Sem vetores a busca cai para BM25: indexar sem embeddings é melhor do que não indexar.
            LOG.warn("Embeddings falharam ({}); o índice segue só com BM25.", e.getClass().getSimpleName());
            report.put("falhaNosEmbeddings", e.getClass().getSimpleName());
            return null;
        }
    }
}
