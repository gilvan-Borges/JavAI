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
class EducationRepository implements ContentRepository<EducationInput> {

    private static final String COLUMNS = """
            id, institution, course_pt, course_en, started_on, ended_on, completion, visibility, status,
            updated_at, updated_by""";

    private static final String LIST = "SELECT " + COLUMNS + " FROM content.education ORDER BY started_on DESC";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.education WHERE id = :id";

    private static final String INSERT = """
            INSERT INTO content.education (institution, course_pt, course_en, started_on, ended_on, completion,
                visibility, updated_by)
            VALUES (:institution, :coursePt, :courseEn, :startedOn, :endedOn, :completion, :visibility, :actor)
            RETURNING
            """ + COLUMNS;

    private static final String UPDATE = """
            UPDATE content.education
            SET institution = :institution, course_pt = :coursePt, course_en = :courseEn,
                started_on = :startedOn, ended_on = :endedOn, completion = :completion,
                visibility = :visibility, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String STATUS = """
            UPDATE content.education SET status = :status, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String DELETE = "DELETE FROM content.education WHERE id = :id";

    private final JdbcClient jdbc;

    EducationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ContentItem<EducationInput>> list() {
        return jdbc.sql(LIST).query(this::map).list();
    }

    @Override
    public Optional<ContentItem<EducationInput>> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(this::map).optional();
    }

    @Override
    public ContentItem<EducationInput> create(EducationInput input, String actor) {
        return jdbc.sql(INSERT).params(params(input, actor)).query(this::map).single();
    }

    @Override
    public Optional<ContentItem<EducationInput>> update(UUID id, EducationInput input, String actor) {
        var params = params(input, actor);
        params.put("id", id);
        return jdbc.sql(UPDATE).params(params).query(this::map).optional();
    }

    @Override
    public Optional<ContentItem<EducationInput>> changeStatus(UUID id, ContentStatus status, String actor) {
        return jdbc.sql(STATUS).param("id", id).param("status", status.db()).param("actor", actor)
                .query(this::map).optional();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql(DELETE).param("id", id).update() > 0;
    }

    private static Map<String, Object> params(EducationInput in, String actor) {
        var p = new HashMap<String, Object>();
        p.put("institution", Sql.text(in.institution()));
        p.put("coursePt", Sql.text(in.coursePt()));
        p.put("courseEn", Sql.text(in.courseEn()));
        p.put("startedOn", in.startedOn());
        p.put("endedOn", in.endedOn());
        p.put("completion", in.completion());
        p.put("visibility", in.visibility().db());
        p.put("actor", actor);
        return p;
    }

    private ContentItem<EducationInput> map(ResultSet rs, int row) throws SQLException {
        return Sql.item(rs, new EducationInput(
                rs.getString("institution"),
                rs.getString("course_pt"),
                rs.getString("course_en"),
                Sql.date(rs, "started_on"),
                Sql.date(rs, "ended_on"),
                rs.getString("completion"),
                Visibility.fromDb(rs.getString("visibility"))));
    }
}
