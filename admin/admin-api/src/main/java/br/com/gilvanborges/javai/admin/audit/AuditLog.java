package br.com.gilvanborges.javai.admin.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Grava e lista a auditoria. A tabela só aceita novas linhas: o papel javai_admin não tem UPDATE nem DELETE. */
@Component
public class AuditLog {

    private static final String INSERT = """
            INSERT INTO ops.audit_log (actor, action, entity, entity_id, diff)
            VALUES (:actor, :action, :entity, :entityId, CAST(:diff AS jsonb))""";

    private static final String LIST = """
            SELECT id, at, actor, action, entity, entity_id, diff
            FROM ops.audit_log
            WHERE at >= :from AND at < :to
            ORDER BY at DESC, id DESC
            LIMIT 500""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String actor, AuditAction action, String entity, String entityId, Object before, Object after) {
        String diff = null;
        if (before != null || after != null) {
            var values = new LinkedHashMap<String, Object>();
            values.put("before", before);
            values.put("after", after);
            diff = json.writeValueAsString(values);
        }
        jdbc.sql(INSERT)
                .param("actor", actor)
                .param("action", action.db())
                .param("entity", entity)
                .param("entityId", entityId)
                .param("diff", diff)
                .update();
    }

    public List<AuditEntry> list(OffsetDateTime from, OffsetDateTime to) {
        return jdbc.sql(LIST)
                .param("from", from)
                .param("to", to)
                .query((rs, row) -> new AuditEntry(
                        rs.getLong("id"),
                        rs.getObject("at", OffsetDateTime.class),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity"),
                        rs.getString("entity_id"),
                        readDiff(rs.getString("diff"))))
                .list();
    }

    private Map<String, Object> readDiff(String diff) {
        return diff == null ? null : json.readValue(diff, new TypeReference<>() {
        });
    }

    public record AuditEntry(
            long id,
            OffsetDateTime at,
            String actor,
            String action,
            String entity,
            String entityId,
            Map<String, Object> diff) {
    }
}
