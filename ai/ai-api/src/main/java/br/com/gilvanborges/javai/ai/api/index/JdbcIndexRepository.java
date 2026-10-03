package br.com.gilvanborges.javai.ai.api.index;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.chunk.SourceKind;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import br.com.gilvanborges.javai.ai.core.search.VectorOps;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Lê o índice com o papel {@code ai_reader}. O RLS do banco já limita as três tabelas à versão ativa, então o SQL
 * não filtra por versão: mesmo que alguém esquecesse, o banco não devolveria outra. Todo SQL é constante, sem
 * valor externo (spec 02, "SQL injection").
 */
@Repository
@ConditionalOnProperty(name = "aiapi.db.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcIndexRepository implements IndexRepository {

    private static final String ACTIVE_VERSION = "SELECT id, embedding_model FROM index.version";

    private static final String ACTIVE_META = """
            SELECT id, activated_at, embedding_model, snapshot_etag, stats::text AS stats FROM index.version""";

    private static final String CHUNKS = """
            SELECT source_kind, source_ref, lang, title, body, url, embedding
            FROM index.chunk ORDER BY id""";

    private static final String EVIDENCE = """
            SELECT skill, repo_full_name, path, line, detector, url
            FROM index.skill_evidence ORDER BY skill, repo_full_name, path, line""";

    private static final int ATTEMPTS = 3;

    private final JdbcClient jdbc;

    public JdbcIndexRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> activeVersionId() {
        return jdbc.sql(ACTIVE_VERSION).query((rs, n) -> rs.getLong("id")).optional();
    }

    @Override
    public Optional<VersionMeta> activeMeta() {
        return jdbc.sql(ACTIVE_META).query((rs, n) -> new VersionMeta(rs.getLong("id"),
                rs.getObject("activated_at", java.time.OffsetDateTime.class) == null ? null
                        : rs.getObject("activated_at", java.time.OffsetDateTime.class).toInstant(),
                rs.getString("embedding_model"), rs.getString("snapshot_etag"), rs.getString("stats"))).optional();
    }

    @Override
    public Optional<ActiveIndex> loadActive() {
        // Uma ativação pode acontecer entre as três leituras. Se a versão mudou no meio, lê de novo.
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            var version = jdbc.sql(ACTIVE_VERSION)
                    .query((rs, n) -> new Version(rs.getLong("id"), rs.getString("embedding_model"))).optional();
            if (version.isEmpty()) {
                return Optional.empty();
            }
            List<Row> rows = jdbc.sql(CHUNKS).query((rs, n) -> new Row(
                    new Chunk(rs.getString("source_kind") + ":" + n, SourceKind.fromDb(rs.getString("source_kind")),
                            rs.getString("source_ref"), rs.getString("lang"), rs.getString("title"),
                            rs.getString("body"), rs.getString("url")),
                    vector(rs.getArray("embedding")))).list();
            List<Evidence> evidence = jdbc.sql(EVIDENCE).query((rs, n) -> new Evidence(
                    rs.getString("skill"), rs.getString("repo_full_name"), rs.getString("path"), rs.getInt("line"),
                    rs.getString("detector"), rs.getString("url"))).list();

            if (activeVersionId().filter(id -> id == version.get().id()).isEmpty()) {
                continue;
            }
            var chunks = rows.stream().map(Row::chunk).toList();
            boolean allVectors = !rows.isEmpty() && rows.stream().allMatch(r -> r.vector() != null);
            List<float[]> vectors = allVectors ? rows.stream().map(Row::vector).toList() : null;
            return Optional.of(new ActiveIndex(version.get().id(), version.get().model(), chunks, vectors, evidence,
                    VectorOps.best()));
        }
        return Optional.empty();
    }

    private static float[] vector(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] boxed = (Object[]) array.getArray();
        float[] out = new float[boxed.length];
        for (int i = 0; i < boxed.length; i++) {
            out[i] = ((Number) boxed[i]).floatValue();
        }
        return out;
    }

    private record Version(long id, String model) {
    }

    private record Row(Chunk chunk, float[] vector) {
    }
}
