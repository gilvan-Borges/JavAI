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
class CertificationRepository implements ContentRepository<CertificationInput> {

    private static final String COLUMNS =
            "id, name, issuer, issued_on, url, visibility, status, updated_at, updated_by";

    private static final String LIST = "SELECT " + COLUMNS + " FROM content.certification ORDER BY issued_on DESC";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.certification WHERE id = :id";

    private static final String INSERT = """
            INSERT INTO content.certification (name, issuer, issued_on, url, visibility, updated_by)
            VALUES (:name, :issuer, :issuedOn, :url, :visibility, :actor)
            RETURNING
            """ + COLUMNS;

    private static final String UPDATE = """
            UPDATE content.certification
            SET name = :name, issuer = :issuer, issued_on = :issuedOn, url = :url, visibility = :visibility,
                updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String STATUS = """
            UPDATE content.certification SET status = :status, updated_at = now(), updated_by = :actor
            WHERE id = :id
            RETURNING
            """ + COLUMNS;

    private static final String DELETE = "DELETE FROM content.certification WHERE id = :id";

    private final JdbcClient jdbc;

    CertificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ContentItem<CertificationInput>> list() {
        return jdbc.sql(LIST).query(this::map).list();
    }

    @Override
    public Optional<ContentItem<CertificationInput>> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query(this::map).optional();
    }

    @Override
    public ContentItem<CertificationInput> create(CertificationInput input, String actor) {
        return jdbc.sql(INSERT).params(params(input, actor)).query(this::map).single();
    }

    @Override
    public Optional<ContentItem<CertificationInput>> update(UUID id, CertificationInput input, String actor) {
        var params = params(input, actor);
        params.put("id", id);
        return jdbc.sql(UPDATE).params(params).query(this::map).optional();
    }

    @Override
    public Optional<ContentItem<CertificationInput>> changeStatus(UUID id, ContentStatus status, String actor) {
        return jdbc.sql(STATUS).param("id", id).param("status", status.db()).param("actor", actor)
                .query(this::map).optional();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.sql(DELETE).param("id", id).update() > 0;
    }

    private static Map<String, Object> params(CertificationInput in, String actor) {
        var p = new HashMap<String, Object>();
        p.put("name", Sql.text(in.name()));
        p.put("issuer", Sql.text(in.issuer()));
        p.put("issuedOn", in.issuedOn());
        p.put("url", Sql.text(in.url()));
        p.put("visibility", in.visibility().db());
        p.put("actor", actor);
        return p;
    }

    private ContentItem<CertificationInput> map(ResultSet rs, int row) throws SQLException {
        return Sql.item(rs, new CertificationInput(
                rs.getString("name"),
                rs.getString("issuer"),
                Sql.date(rs, "issued_on"),
                rs.getString("url"),
                Visibility.fromDb(rs.getString("visibility"))));
    }
}
