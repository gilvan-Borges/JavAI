-- Permissões (spec 02, tabela "Permissões"). Privilégio mínimo: cada papel recebe só o que usa.

GRANT USAGE ON SCHEMA content, ai, ops TO javai_admin;
GRANT USAGE ON SCHEMA ai, ops TO javai_ai;

-- javai_admin: CRUD, indexador e auditoria.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA content TO javai_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON ai.index_version TO javai_admin;
GRANT SELECT, INSERT, DELETE ON ai.chunk, ai.skill_evidence, ai.content_snapshot TO javai_admin;
GRANT SELECT ON ops.usage_daily TO javai_admin;
GRANT SELECT, INSERT ON ops.audit_log TO javai_admin;
GRANT SELECT, INSERT, UPDATE ON ops.index_job TO javai_admin;
GRANT EXECUTE ON FUNCTION ai.activate_index_version(bigint) TO javai_admin;

-- javai_ai: lê o índice (o RLS em V6 limita à versão ativa) e grava contadores. Nada em content.
GRANT SELECT ON ai.index_version, ai.chunk, ai.skill_evidence, ai.content_snapshot TO javai_ai;
GRANT SELECT, INSERT, UPDATE ON ops.usage_daily TO javai_ai;
