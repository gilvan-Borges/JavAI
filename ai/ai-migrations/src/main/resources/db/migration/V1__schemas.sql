-- Schemas do banco da IA (spec 02).
-- O dono de tudo é ai_owner, o papel que roda o Flyway. Os papéis de runtime (ai_indexer e ai_reader) nunca são
-- donos de nada, porque o dono de uma tabela ignora o RLS.

CREATE SCHEMA index;
CREATE SCHEMA ops;

-- Ninguém usa o schema public.
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- Funções novas não nascem executáveis por qualquer papel.
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
