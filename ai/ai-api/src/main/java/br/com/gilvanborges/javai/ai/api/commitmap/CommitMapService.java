package br.com.gilvanborges.javai.ai.api.commitmap;

import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.IndexRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monta o mapa de commits (spec 09): para cada repositório público que o indexador leu, o histórico recente, do commit
 * mais novo para o mais antigo. Só saem hash curto, instante e a primeira linha da mensagem; autor e e-mail nem chegam
 * ao banco. O corpo montado é reaproveitado por {@link #CACHE} enquanto a versão ativa do índice for a mesma.
 */
@Service
public class CommitMapService {

    /** Teto de commits por repositório no que a API devolve. */
    static final int MAX_PER_REPO = 150;
    static final Duration CACHE = Duration.ofMinutes(10);

    private record Cached(long versionId, Instant at, Map<String, Object> body) {
    }

    private final IndexHolder index;
    private final ObjectProvider<CommitMapStore> store;
    private final Clock clock;
    private volatile Cached cached;

    public CommitMapService(IndexHolder index, ObjectProvider<CommitMapStore> store, Clock clock) {
        this.index = index;
        this.store = store;
        this.clock = clock;
    }

    /** @return vazio se ainda não há índice ativo */
    public Optional<Map<String, Object>> snapshot() {
        ActiveIndex active = index.current().orElse(null);
        if (active == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Cached hit = cached;
        if (hit != null && hit.versionId() == active.versionId() && Duration.between(hit.at(), now).compareTo(CACHE) < 0) {
            return Optional.of(hit.body());
        }
        Instant updatedAt = index.meta().map(IndexRepository.VersionMeta::activatedAt).orElse(now);
        Map<String, Object> body = build(store.getIfAvailable(() -> CommitMapStore.NOOP).recent(MAX_PER_REPO), updatedAt);
        cached = new Cached(active.versionId(), now, body);
        return Optional.of(body);
    }

    static Map<String, Object> build(List<CommitMapStore.Commit> rows, Instant updatedAt) {
        var byRepo = new LinkedHashMap<String, List<CommitMapStore.Commit>>();
        for (CommitMapStore.Commit row : rows) {
            byRepo.computeIfAbsent(row.repoFullName(), k -> new ArrayList<>()).add(row);
        }
        var repos = new ArrayList<Map<String, Object>>();
        int total = 0;
        for (var entry : byRepo.entrySet()) {
            var commits = entry.getValue().stream()
                    .sorted(Comparator.comparing(CommitMapStore.Commit::at).reversed())
                    .limit(MAX_PER_REPO)
                    .map(c -> {
                        var m = new LinkedHashMap<String, Object>();
                        m.put("sha", c.sha().length() > 7 ? c.sha().substring(0, 7) : c.sha());
                        m.put("at", c.at().truncatedTo(ChronoUnit.SECONDS).toString());
                        m.put("subject", c.subject());
                        return (Map<String, Object>) m;
                    }).toList();
            String fullName = entry.getKey();
            var repo = new LinkedHashMap<String, Object>();
            repo.put("name", fullName.substring(fullName.indexOf('/') + 1));
            repo.put("fullName", fullName);
            repo.put("url", "https://github.com/" + fullName);
            repo.put("commits", commits);
            repos.add(repo);
            total += commits.size();
        }
        repos.sort(Comparator.comparingInt((Map<String, Object> r) -> ((List<?>) r.get("commits")).size()).reversed()
                .thenComparing(r -> (String) r.get("name")));

        var out = new LinkedHashMap<String, Object>();
        out.put("updatedAt", updatedAt.truncatedTo(ChronoUnit.SECONDS).toString());
        out.put("totals", Map.of("repos", repos.size(), "commits", total));
        out.put("repos", repos);
        return out;
    }
}
