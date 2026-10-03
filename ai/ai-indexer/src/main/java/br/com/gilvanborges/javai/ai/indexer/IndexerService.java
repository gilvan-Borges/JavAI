package br.com.gilvanborges.javai.ai.indexer;

import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;
import br.com.gilvanborges.javai.ai.indexer.build.IndexBuilder;
import br.com.gilvanborges.javai.ai.indexer.embedding.EmbedderProvider;
import br.com.gilvanborges.javai.ai.indexer.repo.RepoSource;
import br.com.gilvanborges.javai.ai.indexer.snapshot.SnapshotClient;
import br.com.gilvanborges.javai.ai.indexer.sources.PublicSources;
import br.com.gilvanborges.javai.ai.indexer.store.IndexStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Uma execução do indexador (spec 01, "Indexador"): lê o snapshot do CRUD, lê os repositórios ligados, monta o
 * índice, grava como versão nova e a ativa. Se qualquer passo falhar, a versão nova vira "failed" e a ativa
 * continua a que estava: a IA nunca fica sem índice por causa de uma indexação ruim.
 */
@Service
public class IndexerService {

    private static final Logger LOG = LoggerFactory.getLogger(IndexerService.class);
    private static final List<String> LANGS = List.of("pt", "en");

    /** O que dispara a execução. O valor combina com o CHECK de {@code ops.index_job.trigger}. */
    public enum Trigger {
        SNAPSHOT("snapshot"), AGENDADO("agendado"), MANUAL("manual");

        private final String db;

        Trigger(String db) {
            this.db = db;
        }
    }

    public record Outcome(String status, Long versionId, Map<String, Object> report) {
    }

    private final SnapshotClient snapshots;
    private final RepoSource repos;
    private final EmbedderProvider embedders;
    private final IndexStore store;
    private final JsonMapper json;
    private final IndexerProperties properties;
    private final PublicSources sources;
    private final AtomicBoolean running = new AtomicBoolean();
    /** Quando a última reindexação disparada por commit novo começou: segura um repositório que recebe commits a toda hora. */
    private final AtomicReference<Instant> lastRepoTriggered = new AtomicReference<>(Instant.EPOCH);
    /** Quando as fontes públicas foram conferidas pela última vez: elas têm intervalo próprio, maior que o do snapshot. */
    private final AtomicReference<Instant> lastSourceCheck = new AtomicReference<>(Instant.EPOCH);
    private static final String REPO_KEY = "repo:";
    /** Marca que o índice registrou o commit de cada repositório; sem ela, é um índice de antes desta conferência. */
    private static final String TRACKED_KEY = "repos:tracked";

    public IndexerService(SnapshotClient snapshots, RepoSource repos, EmbedderProvider embedders, IndexStore store,
                          JsonMapper json, IndexerProperties properties, PublicSources sources) {
        this.sources = sources;
        this.snapshots = snapshots;
        this.repos = repos;
        this.embedders = embedders;
        this.store = store;
        this.json = json;
        this.properties = properties;
    }

    public boolean running() {
        return running.get();
    }

    /** Nunca lança exceção: o resultado diz se deu certo, foi pulado ou falhou. */
    public Outcome run(Trigger trigger) {
        if (!running.compareAndSet(false, true)) {
            return new Outcome("skipped", null, Map.of("motivo", "já existe uma indexação em andamento"));
        }
        UUID job = store.startJob(trigger.db);
        Long versionId = null;
        try {
            boolean force = trigger != Trigger.SNAPSHOT;
            Map<String, String> previous = force ? Map.of() : previousEtags();

            PublicSources.Read sourceRead = null;
            var fetched = new LinkedHashMap<String, SnapshotClient.Fetch>();
            for (String lang : LANGS) {
                fetched.put(lang, snapshots.fetch(lang, previous.get(lang)));
            }
            if (fetched.values().stream().allMatch(SnapshotClient.Fetch::notModified)) {
                // O snapshot do CRUD não mudou, mas os repositórios podem ter recebido commit: confere o HEAD de cada um.
                List<String> changed = previous.containsKey(TRACKED_KEY) ? repositoriesWithNewCommits(previous)
                        : List.of("(índice anterior não registrava os commits)");
                boolean sourcesDue = sources.properties().any()
                        && Duration.between(lastSourceCheck.get(), Instant.now()).compareTo(sources.properties().checkInterval()) >= 0;
                boolean sourcesChanged = false;
                if (sourcesDue) {
                    lastSourceCheck.set(Instant.now());
                    sourceRead = sources.read();
                    var known = previous;
                    sourcesChanged = !sourceRead.hashes().entrySet().stream()
                            .allMatch(e -> e.getValue().equals(known.get(e.getKey())));
                }
                if (changed.isEmpty() && !sourcesChanged) {
                    var report = Map.<String, Object>of("motivo", "o snapshot, os repositórios e as fontes públicas não mudaram");
                    store.finishJob(job, "skipped", null, report);
                    return new Outcome("skipped", null, report);
                }
                Duration sinceLast = Duration.between(lastRepoTriggered.get(), Instant.now());
                if (!sourcesChanged && sinceLast.compareTo(properties.repoMinInterval()) < 0) {
                    var report = Map.<String, Object>of("motivo", "repositórios com commit novo, aguardando o intervalo mínimo",
                            "repositorios", changed);
                    store.finishJob(job, "skipped", null, report);
                    return new Outcome("skipped", null, report);
                }
                lastRepoTriggered.set(Instant.now());
                LOG.info("Mudança detectada (repositórios: {}, fontes públicas: {}): reindexando.", changed, sourcesChanged);
            }
            // Mudou num idioma: precisamos dos dois inteiros, então o que respondeu 304 é lido de novo.
            for (String lang : LANGS) {
                if (fetched.get(lang).notModified()) {
                    fetched.put(lang, snapshots.fetch(lang, null));
                }
            }

            var data = new LinkedHashMap<String, SnapshotData>();
            var etags = new LinkedHashMap<String, String>();
            fetched.forEach((lang, f) -> {
                data.put(lang, f.data().orElseThrow());
                etags.put(lang, f.etag());
            });

            if (sources.properties().any()) {
                lastSourceCheck.set(Instant.now());
                if (sourceRead == null) {
                    sourceRead = sources.read();
                }
            }
            var repoNames = new LinkedHashSet<>(linkedRepositories(data));
            if (sourceRead != null) {
                repoNames.addAll(sources.repositories(sourceRead.repos()));
            }
            var repoReads = repoNames.stream().map(repos::read).toList();
            // O commit lido de cada repositório entra junto com os ETags do snapshot: é com ele que o próximo ciclo compara.
            etags.put(TRACKED_KEY, "1");
            repoReads.stream().filter(r -> !r.wasSkipped() && r.commitSha() != null)
                    .forEach(r -> etags.put(REPO_KEY + r.fullName(), r.commitSha()));
            Optional<br.com.gilvanborges.javai.ai.core.embedding.Embedder> embedder = embedders.get();
            if (sourceRead != null) {
                etags.putAll(sourceRead.hashes());
            }
            var built = new IndexBuilder(properties.maxChunksPerRepo()).build(data, repoReads,
                    sourceRead == null ? List.of() : sourceRead.chunks(),
                    sourceRead == null ? List.of() : sourceRead.status(), embedder);

            versionId = store.createVersion(embedder.map(e -> e.modelId()).orElse(null), json.writeValueAsString(etags));
            try {
                store.saveChunks(versionId, built.chunks(), built.embeddings());
                store.saveEvidence(versionId, built.evidence());
                store.saveStats(versionId, built.report());
                store.activate(versionId);
            } catch (RuntimeException e) {
                store.markFailed(versionId, Map.of("motivo", describe(e)));
                throw e;
            }
            if (trigger == Trigger.MANUAL) {
                built.report().put("versoesAntigasApagadas", store.purgeRetired());
            }
            store.finishJob(job, "succeeded", versionId, built.report());
            LOG.info("Índice {} ativo: {} trechos, {} evidências.", versionId, built.chunks().size(), built.evidence().size());
            return new Outcome("succeeded", versionId, built.report());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Indexação falhou: {}", e.getClass().getSimpleName(), e);
            var report = Map.<String, Object>of("motivo", describe(e));
            store.finishJob(job, "failed", versionId, report);
            return new Outcome("failed", versionId, report);
        } finally {
            running.set(false);
        }
    }

    /** Os repositórios que o dono ligou no CRUD, sem repetir (o snapshot em português e o em inglês listam os mesmos). */
    private static List<String> linkedRepositories(Map<String, SnapshotData> data) {
        var names = new LinkedHashSet<String>();
        data.values().forEach(s -> s.repositories().forEach(r -> names.add(r.fullName())));
        return List.copyOf(names);
    }

    /** Os repositórios que a versão ativa leu e cujo commit mais recente no GitHub já é outro. */
    private List<String> repositoriesWithNewCommits(Map<String, String> previous) {
        var changed = new java.util.ArrayList<String>();
        previous.forEach((key, knownSha) -> {
            if (key.startsWith(REPO_KEY)) {
                String name = key.substring(REPO_KEY.length());
                repos.head(name).filter(current -> !current.equals(knownSha)).ifPresent(current -> changed.add(name));
            }
        });
        return changed;
    }

    private Map<String, String> previousEtags() {
        return store.activeSnapshotEtag().map(value -> {
            try {
                return json.readValue(value, new TypeReference<Map<String, String>>() { });
            } catch (RuntimeException e) {
                return Map.<String, String>of(); // formato desconhecido: trata como "nunca li"
            }
        }).orElse(Map.of());
    }

    /** Uma frase curta e sem rastro de pilha para o relatório. */
    private static String describe(Throwable e) {
        String message = e.getMessage() == null ? "" : ": " + e.getMessage().replaceAll("\\s+", " ");
        String text = e.getClass().getSimpleName() + message;
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
