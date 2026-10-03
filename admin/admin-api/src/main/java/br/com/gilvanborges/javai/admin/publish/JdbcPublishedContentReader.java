package br.com.gilvanborges.javai.admin.publish;

import br.com.gilvanborges.javai.admin.domain.content.Link;
import br.com.gilvanborges.javai.admin.domain.content.Localized;
import br.com.gilvanborges.javai.admin.domain.content.LocalizedList;
import br.com.gilvanborges.javai.admin.domain.content.PublishedContent;
import br.com.gilvanborges.javai.admin.domain.publish.PublishedContentReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

/**
 * Lê o conteúdo publicado e público com uma lista explícita de colunas (spec 02). Nenhuma consulta daqui
 * seleciona {@code private_notes}, status ou visibilidade, e um teste quebra se alguma passar a selecionar.
 */
@Component
class JdbcPublishedContentReader implements PublishedContentReader {

    static final String PROFILE = """
            SELECT full_name, headline_pt, headline_en, summary_pt, summary_en, location, links,
                availability_pt, availability_en
            FROM content.profile
            WHERE status = 'published' AND visibility = 'public'""";

    static final String EXPERIENCES = """
            SELECT company, role_pt, role_en, started_on, ended_on, description_pt, description_en,
                highlights_pt, highlights_en, stack
            FROM content.experience
            WHERE status = 'published' AND visibility = 'public'
            ORDER BY sort_order, started_on DESC""";

    static final String PROJECTS = """
            SELECT slug, name, summary_pt, summary_en, problem_pt, problem_en, solution_pt, solution_en,
                results_pt, results_en, stack, repo_full_name, url, featured
            FROM content.project
            WHERE status = 'published' AND visibility = 'public'
            ORDER BY sort_order, name""";

    static final String SKILLS = """
            SELECT name, category, declared, notes_pt, notes_en
            FROM content.skill
            WHERE status = 'published' AND visibility = 'public'
            ORDER BY category, name""";

    static final String EDUCATION = """
            SELECT institution, course_pt, course_en, started_on, ended_on, completion
            FROM content.education
            WHERE status = 'published' AND visibility = 'public'
            ORDER BY started_on DESC""";

    static final String CERTIFICATIONS = """
            SELECT name, issuer, issued_on, url
            FROM content.certification
            WHERE status = 'published' AND visibility = 'public'
            ORDER BY issued_on DESC""";

    static final String FAQ = """
            SELECT question_pt, question_en, answer_pt, answer_en
            FROM content.faq
            WHERE status = 'published' AND visibility = 'public'
            ORDER BY sort_order, created_at""";

    /**
     * Só repositório ligado, que não seja fork nem privado. O banco já impede ligar fork e privado (CHECK), e este
     * filtro repete a regra: quem lê aqui nunca depende só de uma trava.
     */
    static final String REPOSITORIES = """
            SELECT full_name, kind, note_pt, note_en
            FROM content.github_repo
            WHERE enabled AND NOT is_fork AND NOT is_private
            ORDER BY full_name""";

    private static final TypeReference<List<Link>> LINKS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate readOnlySnapshot;

    JdbcPublishedContentReader(JdbcClient jdbc, JsonMapper json, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        // Todas as tabelas lidas no mesmo instante: uma edição no meio da publicação não gera snapshot misturado.
        this.readOnlySnapshot = new TransactionTemplate(transactions);
        this.readOnlySnapshot.setReadOnly(true);
        this.readOnlySnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public PublishedContent read() {
        return readOnlySnapshot.execute(status -> new PublishedContent(
                jdbc.sql(PROFILE).query(this::profile).optional().orElse(null),
                jdbc.sql(EXPERIENCES).query((rs, row) -> new PublishedContent.Experience(
                        rs.getString("company"),
                        localized(rs, "role"),
                        date(rs, "started_on"),
                        date(rs, "ended_on"),
                        localized(rs, "description"),
                        new LocalizedList(list(rs, "highlights_pt"), list(rs, "highlights_en")),
                        list(rs, "stack"))).list(),
                jdbc.sql(PROJECTS).query((rs, row) -> new PublishedContent.Project(
                        rs.getString("slug"),
                        rs.getString("name"),
                        localized(rs, "summary"),
                        localized(rs, "problem"),
                        localized(rs, "solution"),
                        localized(rs, "results"),
                        list(rs, "stack"),
                        rs.getString("repo_full_name"),
                        rs.getString("url"),
                        rs.getBoolean("featured"))).list(),
                jdbc.sql(SKILLS).query((rs, row) -> new PublishedContent.Skill(
                        rs.getString("name"),
                        rs.getString("category"),
                        rs.getBoolean("declared"),
                        localized(rs, "notes"))).list(),
                jdbc.sql(EDUCATION).query((rs, row) -> new PublishedContent.Education(
                        rs.getString("institution"),
                        localized(rs, "course"),
                        date(rs, "started_on"),
                        date(rs, "ended_on"),
                        rs.getString("completion"))).list(),
                jdbc.sql(CERTIFICATIONS).query((rs, row) -> new PublishedContent.Certification(
                        rs.getString("name"),
                        rs.getString("issuer"),
                        date(rs, "issued_on"),
                        rs.getString("url"))).list(),
                jdbc.sql(FAQ).query((rs, row) -> new PublishedContent.Faq(
                        localized(rs, "question"),
                        localized(rs, "answer"))).list(),
                jdbc.sql(REPOSITORIES).query((rs, row) -> new PublishedContent.Repository(
                        rs.getString("full_name"),
                        rs.getString("kind"),
                        localized(rs, "note"))).list()));
    }

    private PublishedContent.Profile profile(ResultSet rs, int row) throws SQLException {
        return new PublishedContent.Profile(
                rs.getString("full_name"),
                localized(rs, "headline"),
                localized(rs, "summary"),
                rs.getString("location"),
                json.readValue(rs.getString("links"), LINKS),
                localized(rs, "availability"));
    }

    private static Localized localized(ResultSet rs, String prefix) throws SQLException {
        return new Localized(rs.getString(prefix + "_pt"), rs.getString(prefix + "_en"));
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    private static List<String> list(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
