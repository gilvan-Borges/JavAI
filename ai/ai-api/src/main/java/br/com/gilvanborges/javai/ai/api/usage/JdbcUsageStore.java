package br.com.gilvanborges.javai.ai.api.usage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Contadores no banco da IA, com o papel {@code ai_reader} (que só pode ler e gravar {@code ops.usage_daily}). */
@Repository
@ConditionalOnProperty(name = "aiapi.db.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcUsageStore implements UsageStore {

    private static final String SPENT = "SELECT coalesce(sum(cost_usd), 0) FROM ops.usage_daily WHERE day = :day";

    private static final String UPSERT = """
            INSERT INTO ops.usage_daily (day, model, requests, refusals, retracts, tokens_in, tokens_out, cost_usd,
                                         latency_ms_p50, latency_ms_p90, mcp_calls)
            VALUES (:day, :model, :requests, :refusals, :retracts, :tokensIn, :tokensOut, :cost, :p50, :p90, :mcp)
            ON CONFLICT (day, model) DO UPDATE SET
                requests = ops.usage_daily.requests + EXCLUDED.requests,
                refusals = ops.usage_daily.refusals + EXCLUDED.refusals,
                retracts = ops.usage_daily.retracts + EXCLUDED.retracts,
                tokens_in = ops.usage_daily.tokens_in + EXCLUDED.tokens_in,
                tokens_out = ops.usage_daily.tokens_out + EXCLUDED.tokens_out,
                cost_usd = ops.usage_daily.cost_usd + EXCLUDED.cost_usd,
                latency_ms_p50 = coalesce(EXCLUDED.latency_ms_p50, ops.usage_daily.latency_ms_p50),
                latency_ms_p90 = coalesce(EXCLUDED.latency_ms_p90, ops.usage_daily.latency_ms_p90),
                mcp_calls = ops.usage_daily.mcp_calls + EXCLUDED.mcp_calls""";

    private static final String SINCE = """
            SELECT day, model, requests, refusals, retracts, tokens_in, tokens_out, cost_usd,
                   latency_ms_p50, latency_ms_p90, mcp_calls
            FROM ops.usage_daily WHERE day >= :from ORDER BY day, model""";

    private final JdbcClient jdbc;

    public JdbcUsageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal spentOn(LocalDate day) {
        return jdbc.sql(SPENT).param("day", day).query(BigDecimal.class).single();
    }

    @Override
    public void add(Day d) {
        jdbc.sql(UPSERT)
                .param("day", d.day()).param("model", d.model())
                .param("requests", d.requests()).param("refusals", d.refusals()).param("retracts", d.retracts())
                .param("tokensIn", d.tokensIn()).param("tokensOut", d.tokensOut()).param("cost", d.costUsd())
                .param("p50", d.latencyP50(), java.sql.Types.INTEGER).param("p90", d.latencyP90(), java.sql.Types.INTEGER).param("mcp", d.mcpCalls())
                .update();
    }

    @Override
    public List<Day> since(LocalDate from) {
        return jdbc.sql(SINCE).param("from", from).query((rs, n) -> new Day(
                rs.getObject("day", LocalDate.class), rs.getString("model"), rs.getInt("requests"),
                rs.getInt("refusals"), rs.getInt("retracts"), rs.getLong("tokens_in"), rs.getLong("tokens_out"),
                rs.getBigDecimal("cost_usd"), (Integer) rs.getObject("latency_ms_p50"),
                (Integer) rs.getObject("latency_ms_p90"), rs.getInt("mcp_calls"))).list();
    }
}
