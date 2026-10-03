package br.com.gilvanborges.javai.ai.indexer.store;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso ao banco da IA com o papel {@code ai_indexer}. Todo SQL é uma constante com parâmetros nomeados; nenhum
 * valor vindo de fora vira texto de SQL (spec 02, "SQL injection").
 */
@Repository
public class IndexStore {

    private static final String CREATE_VERSION = """
            INSERT INTO index.version (embedding_model, snapshot_etag) VALUES (:model, :etag) RETURNING id""";

    private static final String INSERT_CHUNK = """
            INSERT INTO index.chunk (version_id, source_kind, source_ref, lang, title, body, url, embedding, body_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_EVIDENCE = """
            INSERT INTO index.skill_evidence (version_id, skill, repo_full_name, path, line, detector, url)
            VALUES (?, ?, ?, ?, ?, ?, ?)""";

    private static final String ACTIVATE = "SELECT index.activate_version(:version)";

    private static final String MARK_FAILED =
            "UPDATE index.version SET status = 'failed', stats = CAST(:stats AS jsonb) WHERE id = :version AND status = 'building'";

    private static final String SET_STATS = "UPDATE index.version SET stats = CAST(:stats AS jsonb) WHERE id = :version";

    private static final String ACTIVE_ETAG = "SELECT snapshot_etag FROM index.version WHERE status = 'active'";

    private static final String START_JOB = "INSERT INTO ops.index_job (trigger) VALUES (:trigger) RETURNING id";

    private static final String FINISH_JOB = """
            UPDATE ops.index_job
            SET status = :status, finished_at = now(), version_id = :version, report = CAST(:report AS jsonb)
            WHERE id = :id""";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JsonMapper json;

    public IndexStore(JdbcClient jdbc, JdbcTemplate template, JsonMapper json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    public long createVersion(String embeddingModel, String snapshotEtag) {
        return jdbc.sql(CREATE_VERSION).param("model", embeddingModel).param("etag", snapshotEtag).query(Long.class).single();
    }

    /** @param embeddings um vetor por trecho, ou {@code null} quando o modelo não estava disponível */
    public void saveChunks(long versionId, List<Chunk> chunks, List<float[]> embeddings) {
        List<Integer> positions = java.util.stream.IntStream.range(0, chunks.size()).boxed().toList();
        template.batchUpdate(INSERT_CHUNK, positions, 200, (ps, i) -> {
            Chunk chunk = chunks.get(i);
            ps.setLong(1, versionId);
            ps.setString(2, chunk.kind().db());
            ps.setString(3, chunk.sourceRef());
            ps.setString(4, chunk.lang());
            ps.setString(5, chunk.title());
            ps.setString(6, chunk.body());
            ps.setString(7, chunk.url());
            ps.setArray(8, embeddings == null ? null : floatArray(ps.getConnection(), embeddings.get(i)));
            ps.setString(9, sha256(chunk.body()));
        });
    }

    public void saveEvidence(long versionId, List<Evidence> evidence) {
        template.batchUpdate(INSERT_EVIDENCE, evidence, 200, (ps, e) -> {
            ps.setLong(1, versionId);
            ps.setString(2, e.skill());
            ps.setString(3, e.repository());
            ps.setString(4, e.path());
            ps.setInt(5, e.line());
            ps.setString(6, e.detector());
            ps.setString(7, e.url());
        });
    }

    public void saveStats(long versionId, Map<String, Object> stats) {
        jdbc.sql(SET_STATS).param("version", versionId).param("stats", json.writeValueAsString(stats)).update();
    }

    /** Ativa a versão, aposenta a anterior e avisa o ai-api com NOTIFY, tudo numa transação no banco. */
    public void activate(long versionId) {
        jdbc.sql(ACTIVATE).param("version", versionId).query().listOfRows();
    }

    public void markFailed(long versionId, Map<String, Object> stats) {
        jdbc.sql(MARK_FAILED).param("version", versionId).param("stats", json.writeValueAsString(stats)).update();
    }

    /** O ETag combinado do snapshot que gerou a versão ativa, se houver. */
    public Optional<String> activeSnapshotEtag() {
        return jdbc.sql(ACTIVE_ETAG).query(String.class).optional();
    }

    public UUID startJob(String trigger) {
        return jdbc.sql(START_JOB).param("trigger", trigger).query(UUID.class).single();
    }

    public void finishJob(UUID id, String status, Long versionId, Map<String, Object> report) {
        jdbc.sql(FINISH_JOB)
                .param("id", id)
                .param("status", status)
                .param("version", versionId)
                .param("report", json.writeValueAsString(report))
                .update();
    }

    private static java.sql.Array floatArray(java.sql.Connection connection, float[] vector) throws SQLException {
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            boxed[i] = vector[i];
        }
        return connection.createArrayOf("float4", boxed);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 sempre existe no JDK.", e);
        }
    }
}
