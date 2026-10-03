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
class ProjectRepository implements ContentRepository<ProjectInput> {

    private static final String COLUMNS = """
            id, slug, name, summary_pt, summary_en, problem_pt, problem_en, solution_pt, solution_en, results_pt,
            results_en, stack, repo_full_name, url, featured, sort_order, private_notes, visibility, status,
            updated_at, updated_by""";

    private static final String LIST = "SELECT " + COLUMNS + " FROM content.project ORDER BY sort_order, name";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.project WHERE id = :id";

    private static final String INSERT = """
            INSERT INTO content.project (slug, name, summary_pt, summary_en, problem_pt, problem_en, solution_pt,
                solution_en, results_pt, results_en, stack, repo_full_name, url, featured, sort_order,
                private_notes, visibility, updated_by)
            VALUES (:slug, :name, :summaryPt, :summaryEn, :problemPt, :problemEn, :solutionPt, :solutionEn,
                :resultsPt, :resultsEn, :stack, :repoFullName, :url, :featured, :sortOrder, :privateNotes,
                :visibility, :actor)
            RETURNING
            """ + COLUMNS;

    private static final String UPDATE = """
            UPDATE content.project
            SET slug = :slug, name = :name, summary_pt = :summaryPt, summary_en = :summaryEn,
                problem_pt = :problemPt, problem_en = :problemEn, solution_pt = :solutionPt,
                solution_en = :solutionEn, results_pt = :resultsPt, results_en = :resultsEn, stack = :stack,
                repo_full_name = :repoFullName, url = :url, featured = :featured, sort_order = :sortOrder,
                private_notes = :privateNotes, visibility = :visibility, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String STATUS = """
            UPDATE content.project SET status = :status, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String DELETE = "DELETE FROM content.project WHERE id = :id";

    private final JdbcClient jdbc;

    ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ContentItem<ProjectInput>> list() {
        return jdbc.sql(LIST).query(this::map).list();
    }

    @Override
    public Optional<ContentItem<ProjectInput>> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(this::map).optional();
    }

    @Override
    public ContentItem<ProjectInput> create(ProjectInput input, String actor) {
        return jdbc.sql(INSERT).params(params(input, actor)).query(this::map).single();
    }

    @Override
    public Optional<ContentItem<ProjectInput>> update(UUID id, ProjectInput input, String actor) {
        var params = params(input, actor);
        params.put("id", id);
        return jdbc.sql(UPDATE).params(params).query(this::map).optional();
    }

    @Override
    public Optional<ContentItem<ProjectInput>> changeStatus(UUID id, ContentStatus status, String actor) {
        return jdbc.sql(STATUS).param("id", id).param("status", status.db()).param("actor", actor)
                .query(this::map).optional();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql(DELETE).param("id", id).update() > 0;
    }

    private static Map<String, Object> params(ProjectInput in, String actor) {
        var p = new HashMap<String, Object>();
        p.put("slug", Sql.text(in.slug()));
        p.put("name", Sql.text(in.name()));
        p.put("summaryPt", Sql.text(in.summaryPt()));
        p.put("summaryEn", Sql.text(in.summaryEn()));
        p.put("problemPt", Sql.text(in.problemPt()));
        p.put("problemEn", Sql.text(in.problemEn()));
        p.put("solutionPt", Sql.text(in.solutionPt()));
        p.put("solutionEn", Sql.text(in.solutionEn()));
        p.put("resultsPt", Sql.text(in.resultsPt()));
        p.put("resultsEn", Sql.text(in.resultsEn()));
        p.put("stack", Sql.textArray(in.stack()));
        p.put("repoFullName", Sql.text(in.repoFullName()));
        p.put("url", Sql.text(in.url()));
        p.put("featured", in.featured());
        p.put("sortOrder", in.sortOrder() == null ? 0 : in.sortOrder());
        p.put("privateNotes", Sql.text(in.privateNotes()));
        p.put("visibility", in.visibility().db());
        p.put("actor", actor);
        return p;
    }

    private ContentItem<ProjectInput> map(ResultSet rs, int row) throws SQLException {
        return Sql.item(rs, new ProjectInput(
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("summary_pt"),
                rs.getString("summary_en"),
                rs.getString("problem_pt"),
                rs.getString("problem_en"),
                rs.getString("solution_pt"),
                rs.getString("solution_en"),
                rs.getString("results_pt"),
                rs.getString("results_en"),
                Sql.list(rs, "stack"),
                rs.getString("repo_full_name"),
                rs.getString("url"),
                rs.getBoolean("featured"),
                rs.getInt("sort_order"),
                rs.getString("private_notes"),
                Visibility.fromDb(rs.getString("visibility"))));
    }
}
