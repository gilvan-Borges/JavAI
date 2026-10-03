package br.com.gilvanborges.javai.admin.publish;

import br.com.gilvanborges.javai.admin.domain.content.Lang;
import br.com.gilvanborges.javai.admin.domain.publish.PublicationStore;
import br.com.gilvanborges.javai.admin.domain.snapshot.ContentSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

@Component
class JdbcPublicationStore implements PublicationStore {

    private static final String CREATE = "INSERT INTO publish.version DEFAULT VALUES RETURNING id";

    private static final String SAVE_SNAPSHOT = """
            INSERT INTO publish.snapshot (version_id, lang, body, etag)
            VALUES (:version, :lang, CAST(:body AS jsonb), :etag)""";

    private static final String SNAPSHOTS = """
            SELECT lang, body::text AS body FROM publish.snapshot WHERE version_id = :version ORDER BY lang""";

    private static final String ACTIVATE = "SELECT publish.activate_version(:version)";

    private static final String MARK_FAILED =
            "UPDATE publish.version SET status = 'failed' WHERE id = :version AND status = 'building'";

    private static final String LIST = "SELECT id, status, created_at, activated_at FROM publish.version ORDER BY id DESC";

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final SnapshotFiles files;

    JdbcPublicationStore(JdbcClient jdbc, JsonMapper json, TransactionTemplate tx, SnapshotFiles files) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.files = files;
    }

    @Override
    public long createVersion() {
        return jdbc.sql(CREATE).query(Long.class).single();
    }

    @Override
    public void saveSnapshot(ContentSnapshot snapshot) {
        String body = json.writeValueAsString(snapshot);
        jdbc.sql(SAVE_SNAPSHOT)
                .param("version", snapshot.version())
                .param("lang", snapshot.lang())
                .param("body", body)
                .param("etag", etag(body))
                .update();
    }

    /**
     * Ativa no banco e exporta os arquivos na mesma transação: se a exportação falhar, a ativação é desfeita e
     * o banco continua apontando para a versão que está nos arquivos.
     */
    @Override
    public void activate(long version) {
        tx.executeWithoutResult(status -> {
            var snapshots = jdbc.sql(SNAPSHOTS).param("version", version)
                    .query((rs, row) -> new SnapshotFiles.StoredSnapshot(rs.getString("lang"), rs.getString("body")))
                    .list();
            jdbc.sql(ACTIVATE).param("version", version).query().listOfRows();
            if (snapshots.size() != Lang.values().length) {
                throw new IllegalStateException("A versão " + version + " não tem snapshot em todos os idiomas.");
            }
            files.export(snapshots);
        });
    }

    @Override
    public void markFailed(long version) {
        jdbc.sql(MARK_FAILED).param("version", version).update();
    }

    List<VersionView> list() {
        return jdbc.sql(LIST).query((rs, row) -> new VersionView(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("activated_at", OffsetDateTime.class))).list();
    }

    static String etag(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 sempre existe no JDK.", e);
        }
    }

    record VersionView(long id, String status, OffsetDateTime createdAt, OffsetDateTime activatedAt) {
    }
}
