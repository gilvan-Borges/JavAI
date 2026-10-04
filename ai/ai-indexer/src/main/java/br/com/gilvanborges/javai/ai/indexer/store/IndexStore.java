package br.com.gilvanborges.javai.ai.indexer.store;

import br.com.gilvanborges.javai.ai.core.chunk.Chunk;
import br.com.gilvanborges.javai.ai.core.evidence.Evidence;
import br.com.gilvanborges.javai.ai.indexer.repo.RepoSource;
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

    private static final String INSERT_COMMIT = """
            INSERT INTO index.repo_commit (version_id, repo_full_name, sha, committed_at, subject, url)
            VALUES (?, ?, ?, ?, ?, ?)""";

    private static final String ACTIVATE ="SELECT index.activate_version(:version)";

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

    /** O mapa de commits da versão (spec 09): só hash curto, data e primeira linha da mensagem, de repositórios públicos. */
    public void saveCommits(long versionId, Map<String, List<RepoSource.CommitRef>> commits) {
        var rows = new java.util.ArrayList<Object[]>();
        commits.forEach((repo, list) -> list.forEach(c -> rows.add(new Object[]{repo, c})));
        template.batchUpdate(INSERT_COMMIT, rows, 200, (ps, row) -> {
            var c = (RepoSource.CommitRef) row[1];
            ps.setLong(1, versionId);
            ps.setString(2, (String) row[0]);
            ps.setString(3, c.sha());
            ps.setObject(4, c.at().atOffset(java.time.ZoneOffset.UTC));
            ps.setString(5, c.subject());
            ps.setString(6, "https://github.com/" + row[0] + "/commit/" + c.sha());
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

    /** As últimas execuções, da mais nova para a mais velha, para o painel. O relatório é o JSON de contagens e motivos. */
    public List<Map<String, Object>> recentJobs(int limit) {
        return jdbc.sql("SELECT id, trigger, status, started_at, finished_at, version_id, report::text AS report FROM ops.index_job ORDER BY started_at DESC LIMIT :n")
                .param("n", limit).query((rs, i) -> {
                    var row = new java.util.LinkedHashMap<String, Object>();
                    row.put("id", rs.getString("id"));
                    row.put("gatilho", rs.getString("trigger"));
                    row.put("estado", rs.getString("status"));
                    row.put("inicio", String.valueOf(rs.getTimestamp("started_at").toInstant()));
                    var end = rs.getTimestamp("finished_at");
                    row.put("fim", end == null ? null : String.valueOf(end.toInstant()));
                    row.put("versao", rs.getObject("version_id"));
                    row.put("relatorio", json.readTree(rs.getString("report")));
                    return (Map<String, Object>) row;
                }).list();
    }

    /** Apaga as versões aposentadas e os trechos delas: o reset manual deixa só o índice novo, sem rollback. */
    public int purgeRetired() {
        return jdbc.sql("DELETE FROM index.version WHERE status = 'retired'").update();
    }

    /** Apaga TODAS as versões do índice, inclusive a ativa, e os trechos e evidências delas. A IA fica sem conhecimento. */
    public int wipeAll() {
        return jdbc.sql("DELETE FROM index.version").update();
    }

    /** A última execução que fez algo foi "apagar tudo"? Então o indexador automático espera o botão "buscar dados". */
    public boolean wiped() {
        return jdbc.sql("SELECT report->>'acao' FROM ops.index_job WHERE status <> 'skipped' ORDER BY started_at DESC LIMIT 1")
                .query(String.class).optional().filter("apagar"::equals).isPresent();
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
