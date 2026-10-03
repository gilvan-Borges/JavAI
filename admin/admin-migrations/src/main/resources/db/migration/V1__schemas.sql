-- Schemas do banco do CRUD (spec 02).
-- O dono de tudo é a dmin_owner, o papel que roda o Flyway. O papel do app (admin_app) nunca é dono de nada,
-- porque o dono de uma tabela ignora o RLS.

CREATE SCHEMA content;
CREATE SCHEMA publish;
CREATE SCHEMA ops;

-- Ninguém usa o schema public.
REVOKE ALL ON SCHEMA public FROM PUBLIC;

-- Funções novas não nascem executáveis por qualquer papel.
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
