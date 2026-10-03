package br.com.gilvanborges.javai.ai.indexer;

import br.com.gilvanborges.javai.ai.core.embedding.Embedder;
import br.com.gilvanborges.javai.ai.core.snapshot.SnapshotData;
import br.com.gilvanborges.javai.ai.indexer.repo.RepoSource;

import java.util.List;

/** Dados e dublês compartilhados pelos testes do indexador. */
public final class IndexerFixtures {

    private IndexerFixtures() {
    }

    public static SnapshotData snapshot(String lang) {
        boolean pt = lang.equals("pt");
        return new SnapshotData(lang,
                new SnapshotData.Profile("Gilvan Borges", pt ? "Engenheiro de Software" : "Software Engineer",
                        pt ? "Resumo em português." : "Summary in English.", "Rio de Janeiro", null),
                List.of(new SnapshotData.Experience("Vibetex", "Software Engineer", "2025-01-01", null,
                        pt ? "Sistemas ponta a ponta." : "End-to-end systems.", List.of("Deploy"), List.of("Java 21"))),
                List.of(new SnapshotData.Project("api-suporte", "apiSuporte", "Tickets.", "Contexto.", "Solução.", "Resultado.",
                        List.of("Java 21", "RabbitMQ"), "FightConnect2025/apiSuporte", null)),
                List.of(new SnapshotData.Skill("RabbitMQ", "mensageria", true, "Topic exchanges e DLQ.")),
                List.of(),
                List.of(new SnapshotData.Faq("Qual a stack?", "Java e Spring Boot.")),
                List.of(new SnapshotData.Repository("FightConnect2025/apiSuporte", "destaque", "Exemplo público.")));
    }

    public static RepoSource.RepoRead repo(String fullName, String... pathsAndContents) {
        return repoAt(fullName, "a".repeat(40), pathsAndContents);
    }

    public static RepoSource.RepoRead repoAt(String fullName, String commitSha, String... pathsAndContents) {
        var files = new java.util.ArrayList<RepoSource.RepoFile>();
        for (int i = 0; i < pathsAndContents.length; i += 2) {
            files.add(new RepoSource.RepoFile(pathsAndContents[i], pathsAndContents[i + 1]));
        }
        return new RepoSource.RepoRead(fullName, commitSha, files, null, List.of());
    }

    /** Embedder de mentira: um vetor de tamanho fixo e determinístico, sem modelo nenhum. */
    public static Embedder fakeEmbedder(int dimensions) {
        return new Embedder() {
            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public String modelId() {
                return "falso";
            }

            @Override
            public float[] embedQuery(String text) {
                return vector(text);
            }

            @Override
            public float[] embedPassage(String text) {
                return vector(text);
            }

            private float[] vector(String text) {
                var v = new float[dimensions];
                java.util.Arrays.fill(v, (text.length() % 7 + 1) / 10f);
                return v;
            }
        };
    }
}
