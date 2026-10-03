package br.com.gilvanborges.javai.admin.content;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class SkillRepository implements ContentRepository<SkillInput> {

    private static final String COLUMNS =
            "id, name, category, declared, notes_pt, notes_en, visibility, status, updated_at, updated_by";

    private static final String LIST = "SELECT " + COLUMNS + " FROM content.skill ORDER BY category, name";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.skill WHERE id = :id";

    private static final String INSERT = """
            INSERT INTO content.skill (name, category, declared, notes_pt, notes_en, visibility, updated_by)
            VALUES (:name, :category, :declared, :notesPt, :notesEn, :visibility, :actor)
            RETURNING
            """ + COLUMNS;

    private static final String UPDATE = """
            UPDATE content.skill
            SET name = :name, category = :category, declared = :declared, notes_pt = :notesPt,
                notes_en = :notesEn, visibility = :visibility, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String STATUS = """
            UPDATE content.skill SET status = :status, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String DELETE = "DELETE FROM content.skill WHERE id = :id";

    private final JdbcClient jdbc;

    SkillRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ContentItem<SkillInput>> list() {
        return jdbc.sql(LIST).query(this::map).list();
    }

    @Override
    public Optional<ContentItem<SkillInput>> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(this::map).optional();
    }

    @Override
    public ContentItem<SkillInput> create(SkillInput input, String actor) {
        return jdbc.sql(INSERT).params(params(input, actor)).query(this::map).single();
    }

    @Override
    public Optional<ContentItem<SkillInput>> update(UUID id, SkillInput input, String actor) {
        var params = params(input, actor);
        params.put("id", id);
        return jdbc.sql(UPDATE).params(params).query(this::map).optional();
    }

    @Override
    public Optional<ContentItem<SkillInput>> changeStatus(UUID id, ContentStatus status, String actor) {
        return jdbc.sql(STATUS).param("id", id).param("status", status.db()).param("actor", actor)
                .query(this::map).optional();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql(DELETE).param("id", id).update() > 0;
    }

    private static Map<String, Object> params(SkillInput in, String actor) {
        var p = new HashMap<String, Object>();
        p.put("name", Sql.text(in.name()));
        p.put("category", in.category());
        p.put("declared", in.declared());
        p.put("notesPt", Sql.text(in.notesPt()));
        p.put("notesEn", Sql.text(in.notesEn()));
        p.put("visibility", in.visibility().db());
        p.put("actor", actor);
        return p;
    }

    private ContentItem<SkillInput> map(ResultSet rs, int row) throws SQLException {
        return Sql.item(rs, new SkillInput(
                rs.getString("name"),
                rs.getString("category"),
                rs.getBoolean("declared"),
                rs.getString("notes_pt"),
                rs.getString("notes_en"),
                Visibility.fromDb(rs.getString("visibility"))));
    }
}
