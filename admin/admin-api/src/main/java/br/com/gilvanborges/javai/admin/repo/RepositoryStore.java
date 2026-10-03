package br.com.gilvanborges.javai.admin.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Leitura e escrita de {@code content.github_repo}. O CHECK do banco é a última barreira contra fork e privado. */
@Repository
class RepositoryStore {

    /** Uma linha como o Swagger a mostra. */
    public record Row(String fullName, boolean enabled, String kind, String notePt, String noteEn,
                      boolean isFork, String defaultBranch, Integer sizeKb, OffsetDateTime updatedAt, String updatedBy) {
    }

    private static final String COLUMNS =
            "full_name, enabled, kind, note_pt, note_en, is_fork, default_branch, size_kb, updated_at, updated_by";

    private static final String LIST = "SELECT " + COLUMNS + " FROM content.github_repo ORDER BY full_name";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.github_repo WHERE full_name = :fullName";

    private static final String UPSERT = """
            INSERT INTO content.github_repo (full_name, enabled, kind, note_pt, note_en, is_fork, is_private,
                default_branch, size_kb, updated_by)
            VALUES (:fullName, :enabled, :kind, :notePt, :noteEn, :isFork, false, :defaultBranch, :sizeKb, :actor)
            ON CONFLICT (full_name) DO UPDATE SET
                enabled = :enabled, kind = :kind, note_pt = :notePt, note_en = :noteEn, is_fork = :isFork,
                is_private = false, default_branch = :defaultBranch, size_kb = :sizeKb,
                updated_at = now(), updated_by = :actor
            RETURNING
            """ + COLUMNS;

    private static final String DELETE = "DELETE FROM content.github_repo WHERE full_name = :fullName";

    private final JdbcClient jdbc;

    RepositoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Row> list() {
        return jdbc.sql(LIST).query(RepositoryStore::row).list();
    }

    Optional<Row> find(String fullName) {
        return jdbc.sql(FIND).param("fullName", fullName).query(RepositoryStore::row).optional();
    }

    Row upsert(GitHubLookup.Meta meta, RepositoryInput input, String actor) {
        return jdbc.sql(UPSERT)
                .param("fullName", meta.fullName())
                .param("enabled", input.enabled())
                .param("kind", input.kind().db())
                .param("notePt", input.notePt())
                .param("noteEn", input.noteEn())
                .param("isFork", meta.fork())
                .param("defaultBranch", meta.defaultBranch())
                .param("sizeKb", meta.sizeKb())
                .param("actor", actor)
                .query(RepositoryStore::row)
                .single();
    }

    void delete(String fullName) {
        jdbc.sql(DELETE).param("fullName", fullName).update();
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        return new Row(
                rs.getString("full_name"),
                rs.getBoolean("enabled"),
                rs.getString("kind"),
                rs.getString("note_pt"),
                rs.getString("note_en"),
                rs.getBoolean("is_fork"),
                rs.getString("default_branch"),
                rs.getObject("size_kb", Integer.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("updated_by"));
    }
}
