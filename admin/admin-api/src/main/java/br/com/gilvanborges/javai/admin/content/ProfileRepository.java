package br.com.gilvanborges.javai.admin.content;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Repository
class ProfileRepository {

    private static final String COLUMNS = """
            full_name, headline_pt, headline_en, summary_pt, summary_en, location, links, availability_pt,
            availability_en, private_notes, visibility, status, updated_at, updated_by""";

    private static final String FIND = "SELECT " + COLUMNS + " FROM content.profile WHERE id = 1";

    private static final String SAVE = """
            INSERT INTO content.profile (id, full_name, headline_pt, headline_en, summary_pt, summary_en, location,
                links, availability_pt, availability_en, private_notes, visibility, updated_by)
            VALUES (1, :fullName, :headlinePt, :headlineEn, :summaryPt, :summaryEn, :location,
                CAST(:links AS jsonb), :availabilityPt, :availabilityEn, :privateNotes, :visibility, :actor)
            ON CONFLICT (id) DO UPDATE
            SET full_name = EXCLUDED.full_name, headline_pt = EXCLUDED.headline_pt,
                headline_en = EXCLUDED.headline_en, summary_pt = EXCLUDED.summary_pt,
                summary_en = EXCLUDED.summary_en, location = EXCLUDED.location, links = EXCLUDED.links,
                availability_pt = EXCLUDED.availability_pt, availability_en = EXCLUDED.availability_en,
                private_notes = EXCLUDED.private_notes, visibility = EXCLUDED.visibility,
                updated_at = now(), updated_by = EXCLUDED.updated_by
            RETURNING
            """ + COLUMNS;

    private static final String STATUS = """
            UPDATE content.profile SET status = :status, updated_at = now(), updated_by = :actor
            WHERE id = 1
            RETURNING
            """ + COLUMNS;

    private static final TypeReference<List<ProfileInput.Link>> LINKS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    ProfileRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<ProfileItem> find() {
        return jdbc.sql(FIND).query(this::map).optional();
    }

    ProfileItem save(ProfileInput in, String actor) {
        var p = new HashMap<String, Object>();
        p.put("fullName", Sql.text(in.fullName()));
        p.put("headlinePt", Sql.text(in.headlinePt()));
        p.put("headlineEn", Sql.text(in.headlineEn()));
        p.put("summaryPt", Sql.text(in.summaryPt()));
        p.put("summaryEn", Sql.text(in.summaryEn()));
        p.put("location", Sql.text(in.location()));
        p.put("links", json.writeValueAsString(in.links() == null ? List.of() : in.links()));
        p.put("availabilityPt", Sql.text(in.availabilityPt()));
        p.put("availabilityEn", Sql.text(in.availabilityEn()));
        p.put("privateNotes", Sql.text(in.privateNotes()));
        p.put("visibility", in.visibility().db());
        p.put("actor", actor);
        return jdbc.sql(SAVE).params(p).query(this::map).single();
    }

    Optional<ProfileItem> changeStatus(ContentStatus status, String actor) {
        return jdbc.sql(STATUS).param("status", status.db()).param("actor", actor).query(this::map).optional();
    }

    private ProfileItem map(ResultSet rs, int row) throws SQLException {
        var data = new ProfileInput(
                rs.getString("full_name"),
                rs.getString("headline_pt"),
                rs.getString("headline_en"),
                rs.getString("summary_pt"),
                rs.getString("summary_en"),
                rs.getString("location"),
                json.readValue(rs.getString("links"), LINKS),
                rs.getString("availability_pt"),
                rs.getString("availability_en"),
                rs.getString("private_notes"),
                Visibility.fromDb(rs.getString("visibility")));
        return new ProfileItem(
                data,
                ContentStatus.fromDb(rs.getString("status")),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getString("updated_by"));
    }
}
