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
class FaqRepository implements ContentRepository<FaqInput> {

    private static final String COLUMNS = """
            id, question_pt, question_en, answer_pt, answer_en, sort_order, visibility, status, updated_at,
            updated_by""";

    private static final String LIST = "SELECT " + COLUMNS + " FROM content.faq ORDER BY sort_order, created_at";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.faq WHERE id = :id";

    private static final String INSERT = """
            INSERT INTO content.faq (question_pt, question_en, answer_pt, answer_en, sort_order, visibility,
                updated_by)
            VALUES (:questionPt, :questionEn, :answerPt, :answerEn, :sortOrder, :visibility, :actor)
            RETURNING
            """ + COLUMNS;

    private static final String UPDATE = """
            UPDATE content.faq
            SET question_pt = :questionPt, question_en = :questionEn, answer_pt = :answerPt,
                answer_en = :answerEn, sort_order = :sortOrder, visibility = :visibility,
                updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String STATUS = """
            UPDATE content.faq SET status = :status, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String DELETE = "DELETE FROM content.faq WHERE id = :id";

    private final JdbcClient jdbc;

    FaqRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ContentItem<FaqInput>> list() {
        return jdbc.sql(LIST).query(this::map).list();
    }

    @Override
    public Optional<ContentItem<FaqInput>> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(this::map).optional();
    }

    @Override
    public ContentItem<FaqInput> create(FaqInput input, String actor) {
        return jdbc.sql(INSERT).params(params(input, actor)).query(this::map).single();
    }

    @Override
    public Optional<ContentItem<FaqInput>> update(UUID id, FaqInput input, String actor) {
        var params = params(input, actor);
        params.put("id", id);
        return jdbc.sql(UPDATE).params(params).query(this::map).optional();
    }

    @Override
    public Optional<ContentItem<FaqInput>> changeStatus(UUID id, ContentStatus status, String actor) {
        return jdbc.sql(STATUS).param("id", id).param("status", status.db()).param("actor", actor)
                .query(this::map).optional();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql(DELETE).param("id", id).update() > 0;
    }

    private static Map<String, Object> params(FaqInput in, String actor) {
        var p = new HashMap<String, Object>();
        p.put("questionPt", Sql.text(in.questionPt()));
        p.put("questionEn", Sql.text(in.questionEn()));
        p.put("answerPt", Sql.text(in.answerPt()));
        p.put("answerEn", Sql.text(in.answerEn()));
        p.put("sortOrder", in.sortOrder() == null ? 0 : in.sortOrder());
        p.put("visibility", in.visibility().db());
        p.put("actor", actor);
        return p;
    }

    private ContentItem<FaqInput> map(ResultSet rs, int row) throws SQLException {
        return Sql.item(rs, new FaqInput(
                rs.getString("question_pt"),
                rs.getString("question_en"),
                rs.getString("answer_pt"),
                rs.getString("answer_en"),
                rs.getInt("sort_order"),
                Visibility.fromDb(rs.getString("visibility"))));
    }
}
