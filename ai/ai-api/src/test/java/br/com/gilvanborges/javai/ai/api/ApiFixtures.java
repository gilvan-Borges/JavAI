package br.com.gilvanborges.javai.ai.api;

import br.com.gilvanborges.javai.ai.api.index.ActiveIndex;
import br.com.gilvanborges.javai.ai.api.index.IndexHolder;
import br.com.gilvanborges.javai.ai.api.index.IndexRepository;
import br.com.gilvanborges.javai.ai.api.usage.UsageStore;
import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SnapshotChunker;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import br.com.gilvanborges.javai.ai.core.search.ScalarVectorOps;
import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Um índice pequeno, montado com o mesmo chunker da produção, para os testes das ferramentas. */
public final class ApiFixtures {

    private ApiFixtures() {
    }

    public static SnapshotData snapshot(String lang) {
        boolean pt = lang.equals("pt");
        return new SnapshotData(lang,
                new SnapshotData.Profile("Gilvan Borges", pt ? "Engenheiro de Software" : "Software Engineer",
                        pt ? "Resumo em português." : "Summary in English.", "Rio de Janeiro", null),
                List.of(new SnapshotData.Experience("Vibetex", "Software Engineer", "2025-01-01", null,
                        pt ? "Sistemas ponta a ponta." : "End-to-end systems.", List.of("Deploy"), List.of("Java 21"))),
                List.of(new SnapshotData.Project("api-suporte", "apiSuporte", pt ? "Tickets de suporte." : "Support tickets.",
                        "Contexto.", pt ? "Publica eventos no RabbitMQ." : "Publishes events to RabbitMQ.", "Resultado.",
                        List.of("Java 21", "RabbitMQ"), "FightConnect2025/apiSuporte", null)),
                List.of(new SnapshotData.Skill("RabbitMQ", "mensageria", true, "Topic exchanges e DLQ."),
                        new SnapshotData.Skill("Flutter", "mobile", true, null)),
                List.of(),
                List.of(new SnapshotData.Faq(pt ? "Qual a stack?" : "What is the stack?", "Java e Spring Boot.")),
                List.of(new SnapshotData.Repository("FightConnect2025/apiSuporte", "destaque", pt ? "Exemplo público." : "Public example.")));
    }

    public static ActiveIndex index() {
        var chunks = new ArrayList<Chunk>();
        chunks.addAll(SnapshotChunker.chunk(snapshot("en")));
        chunks.addAll(SnapshotChunker.chunk(snapshot("pt")));
        chunks.add(new Chunk("en:repo_readme:1", SourceKind.REPO_README, "repo_readme:FightConnect2025/apiSuporte:README.md:Intro",
                "en", "README of apiSuporte", "Support API with RabbitMQ events.",
                "https://github.com/FightConnect2025/apiSuporte/blob/abc/README.md"));
        var evidence = List.of(
                new Evidence("RabbitMQ", "FightConnect2025/apiSuporte", "src/main/java/Publisher.java", 42, "amqp-template",
                        "https://github.com/FightConnect2025/apiSuporte/blob/abc/src/main/java/Publisher.java#L42"),
                new Evidence("RabbitMQ", "FightConnect2025/apiSuporte", "src/main/java/Config.java", 10, "queue-declare",
                        "https://github.com/FightConnect2025/apiSuporte/blob/abc/src/main/java/Config.java#L10"));
        return new ActiveIndex(1, "multilingual-e5-small-int8", chunks, null, evidence, new ScalarVectorOps());
    }

    /** Um repositório de mentira que devolve sempre o mesmo índice (ou nenhum). */
    public static IndexRepository repository(ActiveIndex index) {
        return new IndexRepository() {
            @Override
            public Optional<Long> activeVersionId() {
                return Optional.ofNullable(index).map(ActiveIndex::versionId);
            }

            @Override
            public Optional<ActiveIndex> loadActive() {
                return Optional.ofNullable(index);
            }
        };
    }

    public static IndexHolder holder(ActiveIndex index) {
        var holder = new IndexHolder(repository(index));
        holder.refresh();
        return holder;
    }

    /** Contadores em memória: soma como o upsert do banco e guarda tudo para os testes conferirem. */
    public static final class MemoryStore implements UsageStore {
        public final List<Day> rows = new ArrayList<>();
        public java.math.BigDecimal preset = java.math.BigDecimal.ZERO;

        @Override
        public java.math.BigDecimal spentOn(java.time.LocalDate day) {
            return preset;
        }

        @Override
        public synchronized void add(Day delta) {
            rows.add(delta);
        }

        @Override
        public synchronized List<Day> since(java.time.LocalDate from) {
            return List.copyOf(rows);
        }
    }
}
