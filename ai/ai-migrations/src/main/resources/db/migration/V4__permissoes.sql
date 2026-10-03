-- Permissões do banco da IA (spec 02). Privilégio mínimo: cada papel recebe só o que usa, e nunca TRUNCATE.

GRANT USAGE ON SCHEMA index, ops TO ai_indexer;
GRANT USAGE ON SCHEMA index, ops TO ai_reader;

-- ai_indexer: escreve o índice, ativa versões e registra as execuções.
GRANT SELECT, INSERT, UPDATE, DELETE ON index.version TO ai_indexer;
GRANT SELECT, INSERT, DELETE ON index.chunk, index.skill_evidence TO ai_indexer;
GRANT EXECUTE ON FUNCTION index.activate_version(bigint) TO ai_indexer;
GRANT SELECT, INSERT, UPDATE ON ops.index_job TO ai_indexer;

-- ai_reader: lê o índice (o RLS limita à versão ativa) e grava contadores. Nada de escrita no índice.
GRANT SELECT ON index.version, index.chunk, index.skill_evidence TO ai_reader;
GRANT SELECT, INSERT, UPDATE ON ops.usage_daily TO ai_reader;
