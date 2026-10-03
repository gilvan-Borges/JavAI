package br.com.gilvanborges.javai.admin.publish;

import br.com.gilvanborges.javai.admin.audit.AuditAction;
import br.com.gilvanborges.javai.admin.audit.AuditLog;
import br.com.gilvanborges.javai.admin.domain.publish.PublishContent;
import br.com.gilvanborges.javai.admin.domain.publish.PublishRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Roda a publicação fora da requisição, numa virtual thread, e registra o andamento em {@code ops.publish_job}.
 * Uma publicação por vez: o admin-api é uma instância só, então uma trava em memória basta.
 */
@Service
public class PublishJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PublishJobs.class);

    private static final String INSERT = """
            INSERT INTO ops.publish_job (trigger, actor) VALUES (:trigger, :actor) RETURNING id""";

    private static final String FINISH = """
            UPDATE ops.publish_job
            SET status = :status, finished_at = now(), version_id = :version, report = CAST(:report AS jsonb)
            WHERE id = :id""";

    private static final String FIND = """
            SELECT id, trigger, status, actor, started_at, finished_at, version_id, report
            FROM ops.publish_job WHERE id = :id""";

    private final PublishContent publishContent;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final JsonMapper json;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    PublishJobs(PublishContent publishContent, JdbcClient jdbc, AuditLog audit, JsonMapper json,
                @Qualifier("publishExecutor") Executor executor) {
        this.publishContent = publishContent;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.executor = executor;
    }

    public UUID start(String actor) {
        if (!running.compareAndSet(false, true)) {
            throw new PublishInProgressException();
        }
        try {
            UUID jobId = jdbc.sql(INSERT).param("trigger", "manual").param("actor", actor).query(UUID.class).single();
            executor.execute(() -> run(jobId, actor));
            return jobId;
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    public Optional<PublishJob> find(UUID id) {
        return jdbc.sql(FIND).param("id", id).query((rs, row) -> new PublishJob(
                rs.getObject("id", UUID.class),
                rs.getString("trigger"),
                rs.getString("status"),
                rs.getString("actor"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                (Long) rs.getObject("version_id"),
                json.readValue(rs.getString("report"), new TypeReference<Map<String, Object>>() {
                }))).optional();
    }

    private void run(UUID jobId, String actor) {
        try {
            var outcome = publishContent.publish();
            var report = new LinkedHashMap<String, Object>();
            report.put("version", outcome.version());
            report.put("counts", outcome.counts());
            report.put("portfolioNotified", outcome.portfolioNotified());
            finish(jobId, "succeeded", outcome.version(), report);
            audit.record(actor, AuditAction.PUBLISH, "version", String.valueOf(outcome.version()), null, report);
        } catch (PublishRejectedException e) {
            finish(jobId, "failed", null, Map.of("reason", e.getMessage()));
        } catch (RuntimeException e) {
            LOG.error("Falha na publicação {}", jobId, e);
            finish(jobId, "failed", null, Map.of("reason", "Erro interno. Veja os logs do admin-api."));
        } finally {
            running.set(false);
        }
    }

    private void finish(UUID jobId, String status, Long version, Map<String, Object> report) {
        jdbc.sql(FINISH)
                .param("id", jobId)
                .param("status", status)
                .param("version", version)
                .param("report", json.writeValueAsString(report))
                .update();
    }

    public record PublishJob(
            UUID id,
            String trigger,
            String status,
            String actor,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long version,
            Map<String, Object> report) {
    }
}
