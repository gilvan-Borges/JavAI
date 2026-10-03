# 02 · Dados e acesso

**Status:** rascunho · **Atualizada em:** 2026-10-02

## O princípio

**A IA não consegue vazar o que não consegue ler.** O conteúdo do CRUD fica no `postgres-admin`, numa rede Docker em que nenhum contêiner da IA entra. A IA só recebe o snapshot público, que já tem apenas o que foi publicado e é público. Rascunhos, campos privados e auditoria nunca saem do banco do CRUD.

Cada banco tem os próprios papéis, as próprias migrações e o próprio RLS. Um banco não conhece os papéis do outro.

## Regras comuns aos dois bancos

- **Nenhum papel de runtime é dono de tabela.** O dono de uma tabela ignora o RLS, por isso os apps nunca usam o papel `*_owner`. Toda tabela com RLS também usa `FORCE ROW LEVEL SECURITY`.
- **Os papéis de runtime não têm privilégios especiais:** `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION`.
- **`search_path = pg_catalog`** para os papéis de runtime. Todo SQL usa o nome do schema.
- **Schema `public`:** `REVOKE ALL ON SCHEMA public FROM PUBLIC`.
- **Banco de manutenção:** `REVOKE CONNECT ON DATABASE postgres FROM PUBLIC`.
- **Funções não nascem executáveis por `PUBLIC`.**
- **Senhas fora do Git.** O bootstrap do contêiner de migração roda como superusuário antes do Flyway:
  - cria os papéis que faltam;
  - reaplica as senhas vindas do ambiente com `format('%L')`, nunca concatenando, o que também serve para trocá-las;
  - recusa senhas com menos de 24 caracteres ou repetidas.

  As migrações do Flyway nunca criam papéis.
- **O superusuário `postgres` só é usado pelo bootstrap.** A senha dele fica apenas no ambiente do Postgres e do contêiner de migração.
- **`pg_hba`:** aceita só `scram-sha-256`, só da rede de dados do próprio lado, com cada papel liberado apenas no próprio banco.
- **`logServerErrorDetail=false`** nas URLs JDBC, para que valores de linhas não apareçam em exceções nem em logs.

## Banco do CRUD (`postgres-admin`, banco `admin`)

### Papéis

| Papel | Quem usa | Para quê | Limites |
|---|---|---|---|
| `admin_owner` | `admin-migrate` (Flyway) | Dono de todos os objetos | 3 conexões |
| `admin_app` | `admin-api` | CRUD, publicação, auditoria | 10 conexões, `statement_timeout = 30s`, `idle_in_transaction_session_timeout = 10s` |

### Schemas e permissões de `admin_app`

| Objeto | Permissão |
|---|---|
| `content.*` | SELECT, INSERT, UPDATE, DELETE |
| `publish.version` | SELECT, INSERT, UPDATE, DELETE |
| `publish.snapshot` | SELECT, INSERT, DELETE |
| `publish.activate_version(bigint)` | EXECUTE |
| `ops.audit_log` | SELECT, INSERT (sem UPDATE e sem DELETE: a auditoria só cresce) |
| `ops.publish_job` | SELECT, INSERT, UPDATE |

Ninguém em runtime tem `TRUNCATE`, que ignora o RLS.

### RLS

Todas as tabelas têm RLS com `FORCE` e uma política só, para `admin_app`. Com RLS ligado e sem política, qualquer outro papel lê **zero linhas**. Se um dia alguém der um `GRANT` errado a um papel novo, a tabela continua protegida.

### Modelo de dados

**`content`**

Toda tabela de conteúdo tem as colunas `id uuid`, `status` (`draft` | `published`), `visibility` (`public` | `private`), `created_at`, `updated_at` e `updated_by` (o e-mail vindo do JWT do Access). Há duas exceções:
- `profile` é uma linha única, com `id = 1`;
- `github_repo` usa `enabled` no lugar de `status` e `visibility`.

Os textos existem em duas colunas, `_pt` e `_en`. Um rascunho pode ter campos vazios. Para publicar, o banco exige os campos obrigatórios nos dois idiomas, com um `CHECK (status = 'draft' OR ...)`. Texto vazio vira `NULL`.

| Tabela | Campos principais |
|---|---|
| `profile` | `full_name`, `headline`, `summary`, `location`, `links` (jsonb), `availability`, `private_notes` |
| `experience` | `company`, `role`, `started_on`, `ended_on`, `description`, `highlights` (text[]), `stack` (text[]), `sort_order`, `private_notes` |
| `project` | `slug` (único), `name`, `summary`, `problem`, `solution`, `results`, `stack` (text[]), `repo_full_name`, `url`, `featured`, `sort_order`, `private_notes` |
| `skill` | `name`, `category`, `declared`, `notes` |
| `education` | `institution`, `course`, `started_on`, `ended_on`, `completion` (`cursando` \| `concluido` \| `trancado`) |
| `certification` | `name`, `issuer`, `issued_on`, `url` |
| `faq` | `question`, `answer`, `sort_order` |
| `github_repo` | `full_name` (único), `enabled`, `kind` (`destaque` \| `trabalho` \| `estudo` \| `cliente`), `note`, `is_fork`, `is_private`, `default_branch`, `size_kb`, `last_indexed_at` |

Regras garantidas pelo banco:
- `CHECK (NOT (enabled AND is_fork))` e `CHECK (NOT (enabled AND is_private))`: fork e repositório privado nunca vão para o snapshot.
- `private_notes` não aparece em nenhuma consulta da publicação. Um teste quebra se aparecer.

**`publish`**

| Tabela | Campos principais |
|---|---|
| `version` | `id`, `status` (`building` \| `active` \| `retired` \| `failed`), `created_at`, `activated_at` |
| `snapshot` | `version_id`, `lang`, `body` (jsonb), `etag` |

- **Uma versão ativa por vez:** um índice único parcial garante que no máximo uma linha tenha `status = 'active'`.
- **`publish.activate_version(id)`:**
  - aposenta a versão ativa e ativa a nova;
  - guarda as 3 últimas aposentadas;
  - apaga as falhas com mais de 7 dias.

**`ops`**

| Tabela | Campos principais |
|---|---|
| `audit_log` | `id`, `at`, `actor`, `action`, `entity`, `entity_id`, `diff` (jsonb) |
| `publish_job` | `id`, `version_id`, `trigger`, `status`, `actor`, `started_at`, `finished_at`, `report` (jsonb: contagens e motivos, nunca valores) |

### Testes que provam o acesso

Rodam com Testcontainers, num PostgreSQL 18 de verdade, a cada build:

1. Um papel qualquer, mesmo com `GRANT SELECT` dado de propósito, lê **zero linhas** do conteúdo.
2. `admin_app` tenta `UPDATE` ou `DELETE` em `ops.audit_log` → negado.
3. `admin_app` tenta `TRUNCATE`, `CREATE TABLE` em `content` ou `CREATE TABLE` em `public` → negado.
4. `admin_app` tenta conectar no banco `postgres` → negado.
5. Publicar um item sem os dois idiomas → a constraint falha.
6. Ligar um repositório com `is_fork` ou `is_private` → a constraint falha.
7. Ativar uma versão nova aposenta a anterior. Uma segunda versão ativa viola o índice único.
8. Depois de 5 ativações, sobram 3 versões aposentadas e 1 ativa.
9. Rodar o bootstrap de novo com outra senha → a senha antiga deixa de funcionar e a nova funciona.
10. `admin_app` recebe os limites de sessão da tabela de papéis.
11. As consultas da publicação não selecionam `private_notes` (teste no `admin-api`).

## Banco da IA (`postgres-ai`, banco `ai`) · Fase 2

### Papéis

| Papel | Quem usa | Para quê |
|---|---|---|
| `ai_owner` | `ai-migrate` (Flyway) | Dono de todos os objetos |
| `ai_indexer` | `ai-indexer` | Escreve o índice e ativa versões |
| `ai_reader` | `ai-api` | Lê o índice ativo e grava contadores. `statement_timeout = 2s` |

### Schemas

| Tabela | Campos principais | `ai_indexer` | `ai_reader` |
|---|---|---|---|
| `index.version` | `id`, `status`, `created_at`, `activated_at`, `embedding_model`, `snapshot_etag`, `stats` | tudo | SELECT, só a ativa (RLS) |
| `index.chunk` | `version_id`, `source_kind`, `source_ref`, `lang`, `title`, `body`, `url`, `embedding` (real[384]), `body_hash` | SELECT, INSERT, DELETE | SELECT, só da versão ativa (RLS) |
| `index.skill_evidence` | `version_id`, `skill`, `repo_full_name`, `path`, `line`, `detector`, `url` | SELECT, INSERT, DELETE | SELECT, só da versão ativa (RLS) |
| `ops.usage_daily` | PK (`day`, `model`): `requests`, `refusals`, `retracts`, `tokens_in`, `tokens_out`, `cost_usd`, `mcp_calls` | nada | SELECT, INSERT, UPDATE |
| `ops.index_job` | `id`, `version_id`, `trigger`, `status`, `report` | tudo | nada |

O `ai_reader` não escreve no índice. Se o `ai-api` for comprometido, ele não altera o que a IA sabe.

### Testes (Fase 2)

1. `ai_reader` lê só os trechos da versão ativa.
2. `ai_reader` tenta escrever no índice → negado.
3. `ai_reader` tenta ler `ops.index_job` → negado.
4. Uma consulta de `ai_reader` com mais de 2 s → cancelada.

## SQL injection

1. **Só consultas com parâmetros:** `JdbcClient` com parâmetros nomeados, e cada SQL escrito por inteiro numa constante. Nada de concatenar string em SQL. Sem JPA: o SQL fica explícito e fácil de auditar.
2. **Ordenação e filtros dinâmicos** usam um enum fechado, nunca o nome de coluna que vem da requisição.
3. **O modelo nunca escreve SQL.** Não existe text-to-SQL em nenhum ponto do sistema.
4. **O Semgrep roda no CI** com as regras de SQL injection para Java.
5. **O privilégio mínimo limita o estrago:** um SQL injection no `ai-api` não alcança o CRUD (outro banco, outra rede) nem escreve no índice.

## Migrações

Cada lado tem o seu módulo de migração. O contêiner roda o bootstrap como superusuário, depois o Flyway como `*_owner`, e sai. O histórico do Flyway fica no schema `flyway`.

```
admin/admin-migrations          ai/ai-migrations (Fase 2)
  V1__schemas.sql                 V1__schemas.sql
  V2__content.sql                 V2__index.sql
  V3__publish.sql                 V3__ops.sql
  V4__ops.sql                     V4__permissoes.sql
  V5__permissoes.sql              V5__rls.sql
  V6__rls.sql
```

## Backups e LGPD

- **Backup:** `pg_dump` diário dos dois bancos, criptografado, guardado fora da VPS, e restauração testada todo mês. O banco da IA pode ser reconstruído do zero a partir do snapshot e do GitHub; o do CRUD não.
- **Dados pessoais no banco:** só os do próprio Gilvan, cadastrados por ele.
- **Visitantes:** nada é guardado. O hash do IP vive só na memória do rate limit e muda todo dia.

## Decisões tomadas

- **Campos bilíngues (2026-10-02):** colunas `_pt` e `_en`.
- **Isolamento (2026-10-02):** um banco por lado, em contêineres e redes separados.

## Decisões em aberto

1. **Versões antigas guardadas:** a proposta é 3 em cada lado.
