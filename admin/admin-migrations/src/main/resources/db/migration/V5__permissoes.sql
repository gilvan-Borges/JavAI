-- Permissões do banco do CRUD (spec 02). Privilégio mínimo: admin_app recebe só o que usa, e nunca TRUNCATE,
-- que ignora o RLS.

GRANT USAGE ON SCHEMA content, publish, ops TO admin_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA content TO admin_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON publish.version TO admin_app;
GRANT SELECT, INSERT, DELETE ON publish.snapshot TO admin_app;
GRANT EXECUTE ON FUNCTION publish.activate_version(bigint) TO admin_app;

GRANT SELECT, INSERT ON ops.audit_log TO admin_app;
GRANT SELECT, INSERT, UPDATE ON ops.publish_job TO admin_app;
