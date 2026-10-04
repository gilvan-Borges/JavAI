package br.com.gilvanborges.javai.ai.api.commitmap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Lê o mapa de commits com o papel {@code ai_reader}. O RLS limita {@code index.repo_commit} à versão ativa, então o
 * SQL não filtra por versão. O teto por repositório vale no próprio SQL e só as colunas do contrato saem do banco.
 */
@Repository
@ConditionalOnProperty(name = "aiapi.db.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCommitMapStore implements CommitMapStore {

    private static final String RECENT = """
            SELECT repo_full_name, sha, committed_at, subject FROM (
                SELECT repo_full_name, sha, committed_at, subject,
                       row_number() OVER (PARTITION BY repo_full_name ORDER BY committed_at DESC, id) AS posicao
                FROM index.repo_commit) c
            WHERE posicao <= :limite
            ORDER BY repo_full_name, posicao""";

    private final JdbcClient jdbc;

    public JdbcCommitMapStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Commit> recent(int perRepo) {
        return jdbc.sql(RECENT).param("limite", perRepo).query((rs, n) -> new Commit(
                rs.getString("repo_full_name"), rs.getString("sha"),
                rs.getObject("committed_at", OffsetDateTime.class).toInstant(), rs.getString("subject"))).list();
    }
}
