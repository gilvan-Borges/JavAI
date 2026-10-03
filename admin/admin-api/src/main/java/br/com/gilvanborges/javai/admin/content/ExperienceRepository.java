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
class ExperienceRepository implements ContentRepository<ExperienceInput> {

    private static final String COLUMNS = """
            id, company, role_pt, role_en, started_on, ended_on, description_pt, description_en, highlights_pt,
            highlights_en, stack, sort_order, private_notes, visibility, status, updated_at, updated_by""";

    private static final String LIST =
            "SELECT " + COLUMNS + " FROM content.experience ORDER BY sort_order, started_on DESC";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.experience WHERE id = :id";

    private static final String INSERT = """
            INSERT INTO content.experience (company, role_pt, role_en, started_on, ended_on, description_pt,
                description_en, highlights_pt, highlights_en, stack, sort_order, private_notes, visibility, updated_by)
            VALUES (:company, :rolePt, :roleEn, :startedOn, :endedOn, :descriptionPt, :descriptionEn,
                :highlightsPt, :highlightsEn, :stack, :sortOrder, :privateNotes, :visibility, :actor)
            RETURNING
            """ + COLUMNS;

    private static final String UPDATE = """
            UPDATE content.experience
            SET company = :company, role_pt = :rolePt, role_en = :roleEn, started_on = :startedOn,
                ended_on = :endedOn, description_pt = :descriptionPt, description_en = :descriptionEn,
                highlights_pt = :highlightsPt, highlights_en = :highlightsEn, stack = :stack,
                sort_order = :sortOrder, private_notes = :privateNotes, visibility = :visibility,
                updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String STATUS = """
            UPDATE content.experience SET status = :status, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String DELETE = "DELETE FROM content.experience WHERE id = :id";

    private final JdbcClient jdbc;

    ExperienceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ContentItem<ExperienceInput>> list() {
        return jdbc.sql(LIST).query(this::map).list();
    }

    @Override
    public Optional<ContentItem<ExperienceInput>> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(this::map).optional();
    }

    @Override
    public ContentItem<ExperienceInput> create(ExperienceInput input, String actor) {
        return jdbc.sql(INSERT).params(params(input, actor)).query(this::map).single();
    }

    @Override
    public Optional<ContentItem<ExperienceInput>> update(UUID id, ExperienceInput input, String actor) {
        var params = params(input, actor);
        params.put("id", id);
        return jdbc.sql(UPDATE).params(params).query(this::map).optional();
    }

    @Override
    public Optional<ContentItem<ExperienceInput>> changeStatus(UUID id, ContentStatus status, String actor) {
        return jdbc.sql(STATUS).param("id", id).param("status", status.db()).param("actor", actor)
                .query(this::map).optional();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql(DELETE).param("id", id).update() > 0;
    }

    private static Map<String, Object> params(ExperienceInput in, String actor) {
        var p = new HashMap<String, Object>();
        p.put("company", Sql.text(in.company()));
        p.put("rolePt", Sql.text(in.rolePt()));
        p.put("roleEn", Sql.text(in.roleEn()));
        p.put("startedOn", in.startedOn());
        p.put("endedOn", in.endedOn());
        p.put("descriptionPt", Sql.text(in.descriptionPt()));
        p.put("descriptionEn", Sql.text(in.descriptionEn()));
        p.put("highlightsPt", Sql.textArray(in.highlightsPt()));
        p.put("highlightsEn", Sql.textArray(in.highlightsEn()));
        p.put("stack", Sql.textArray(in.stack()));
        p.put("sortOrder", in.sortOrder() == null ? 0 : in.sortOrder());
        p.put("privateNotes", Sql.text(in.privateNotes()));
        p.put("visibility", in.visibility().db());
        p.put("actor", actor);
        return p;
    }

    private ContentItem<ExperienceInput> map(ResultSet rs, int row) throws SQLException {
        return Sql.item(rs, new ExperienceInput(
                rs.getString("company"),
                rs.getString("role_pt"),
                rs.getString("role_en"),
                Sql.date(rs, "started_on"),
                Sql.date(rs, "ended_on"),
                rs.getString("description_pt"),
                rs.getString("description_en"),
                Sql.list(rs, "highlights_pt"),
                Sql.list(rs, "highlights_en"),
                Sql.list(rs, "stack"),
                rs.getInt("sort_order"),
                rs.getString("private_notes"),
                Visibility.fromDb(rs.getString("visibility"))));
    }
}
