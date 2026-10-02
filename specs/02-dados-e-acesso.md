# 02 · Dados e acesso

**Status:** rascunho · **Atualizada em:** 2026-10-02

## O princípio

**A IA não consegue vazar o que não consegue ler.** O ai-api se conecta ao banco com um papel que só enxerga o índice publicado. Mesmo que um ataque engane o modelo, ou que haja um bug no ai-api, essa conexão não alcança rascunhos, campos privados nem a auditoria, e não escreve no conteúdo.

## Schemas

| Schema | O que guarda | Quem escreve | Quem lê |
|---|---|---|---|
| `content` | O que o Gilvan cadastra: rascunhos, campos privados, configuração dos repositórios | admin-api | admin-api |
| `ai` | O índice gerado: trechos, evidências, snapshot do conteúdo publicado | admin-api (indexador) | ai-api e admin-api |
| `ops` | Contadores de uso, auditoria, jobs do indexador | ai-api (só uso) e admin-api | admin-api; o ai-api lê só o uso |

## Papéis do PostgreSQL

| Papel | Login? | Quem usa | Para quê |
|---|---|---|---|
| `javai_owner` | Sim, só no deploy | Contêiner `migrate` (Flyway) | É o dono de todos os objetos e aplica as migrações |
| `javai_admin` | Sim | admin-api | CRUD, indexador, auditoria |
| `javai_ai` | Sim | ai-api | Ler o índice ativo e gravar contadores |

- **Nenhum papel de runtime é dono de tabela.** O dono de uma tabela ignora o RLS, por isso os apps nunca usam `javai_owner`. Mesmo assim, toda tabela com RLS usa também `FORCE ROW LEVEL SECURITY`.
- **Os papéis de runtime não têm privilégios especiais:** `NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS`.
- **Limites de sessão:**
  - `javai_ai`: `statement_timeout = 2s`;
  - `javai_admin`: `statement_timeout = 30s`;
  - ambos: `idle_in_transaction_session_timeout = 10s`.
- **Schema `public`:** `REVOKE ALL ON SCHEMA public FROM PUBLIC`.
- **Senhas fora do Git.** Os papéis são criados pelo bootstrap do contêiner `migrate`, que roda como superusuário antes do Flyway. Ele lê as senhas do ambiente e as passa ao PostgreSQL com `format('%L')`, nunca concatenando. Em todo deploy ele reaplica as senhas, então trocar uma senha é só mudar o `.env`. As migrações do Flyway nunca criam papéis.
- **O superusuário `postgres` só é usado pelo bootstrap.** A senha dele fica apenas no `.env` dos contêineres `postgres` e `migrate`.
- **`pg_hba`:** aceita só `scram-sha-256`, só da rede `data` do Docker, com cada papel liberado apenas no banco `javai`.

## Permissões

| Objeto | `javai_admin` | `javai_ai` |
|---|---|---|
| `content.*` | SELECT, INSERT, UPDATE, DELETE | nada |
| `ai.index_version` | SELECT, INSERT, UPDATE | SELECT |
| `ai.chunk`, `ai.skill_evidence`, `ai.content_snapshot` | SELECT, INSERT, DELETE | SELECT (filtrado por RLS) |
| `ops.usage_daily` | SELECT | SELECT, INSERT, UPDATE |
| `ops.audit_log` | SELECT, INSERT | nada |
| `ops.index_job` | SELECT, INSERT, UPDATE | nada |

A auditoria só aceita novas linhas: ninguém em runtime tem UPDATE ou DELETE em `ops.audit_log`.

## RLS

| Tabela | Política | Efeito |
|---|---|---|
| `ai.chunk` | `javai_ai` lê só se `published AND visibility = 'public' AND index_version_id = <versão ativa>` | Se um bug no indexador gravar um trecho privado, a IA mesmo assim não o vê |
| `ai.skill_evidence`, `ai.content_snapshot` | `javai_ai` lê só a versão ativa | A IA não lê versões em construção nem antigas |
| `content.*` | Política só para `javai_admin` | Não existe política para `javai_ai`. Se um `GRANT` errado aparecer um dia, a IA lê **zero linhas** |
| `ai.*` e `ops.*` | `javai_admin` com `USING (true)` | O admin enxerga tudo o que tem permissão de ler |

A terceira linha é a mais importante: RLS ligado e sem política para um papel significa negar tudo por padrão. A tabela fica protegida mesmo contra um erro de permissão.

## Modelo de dados

### `content`

Toda tabela de conteúdo tem as colunas `id uuid`, `status` (`draft` | `published`), `visibility` (`public` | `private`), `created_at`, `updated_at` e `updated_by` (o e-mail vindo do JWT do Access). Há duas exceções:
- `profile` é uma linha única, com `id = 1`;
- `github_repo` usa `enabled` no lugar de `status` e `visibility`.

Os campos de texto existem em duas colunas, `_pt` e `_en`. Um rascunho pode ter campos vazios. Para publicar, o banco exige os campos obrigatórios nos dois idiomas, com um `CHECK (status = 'draft' OR ...)`.

| Tabela | Campos principais |
|---|---|
| `profile` | Linha única: `full_name`, `headline`, `summary`, `location`, `links` (jsonb), `availability`, `private_notes` |
| `experience` | `company`, `role`, `started_on`, `ended_on`, `description`, `highlights` (text[]), `stack` (text[]), `sort_order`, `private_notes` |
| `project` | `slug` (único), `name`, `summary`, `problem`, `solution`, `results`, `stack` (text[]), `repo_full_name`, `url`, `featured`, `sort_order`, `private_notes` |
| `skill` | `name`, `category`, `declared` (bool), `notes` |
| `education` | `institution`, `course`, `started_on`, `ended_on`, `completion` (`cursando` \| `concluido` \| `trancado`) |
| `certification` | `name`, `issuer`, `issued_on`, `url` |
| `faq` | `question`, `answer` |
| `github_repo` | `full_name` (único), `enabled`, `kind` (`destaque` \| `trabalho` \| `estudo` \| `cliente`), `note`, `is_fork`, `is_private`, `default_branch`, `last_indexed_at` |

Regras garantidas pelo banco:
- `CHECK (NOT (enabled AND is_fork))`: um fork nunca é indexado.
- `CHECK (NOT (enabled AND is_private))`: um repositório privado nunca é indexado.
- `private_notes` não aparece na lista de campos do indexador. Um teste quebra se aparecer.

### `ai`

| Tabela | Campos principais |
|---|---|
| `index_version` | `id`, `status` (`building` \| `active` \| `retired` \| `failed`), `created_at`, `activated_at`, `embedding_model`, `stats` (jsonb) |
| `chunk` | `id`, `index_version_id`, `source_kind` (`profile` \| `experience` \| `project` \| `faq` \| `skill` \| `repo_meta` \| `repo_readme` \| `repo_file`), `source_ref`, `lang`, `title`, `body`, `url`, `embedding` (real[384]), `body_hash`, `published`, `visibility` |
| `skill_evidence` | `index_version_id`, `skill`, `repo_full_name`, `path`, `line`, `detector`, `url` |
| `content_snapshot` | `index_version_id`, `lang`, `body` (jsonb), `etag` |

- **Uma versão ativa por vez:** um índice único parcial garante que no máximo uma linha tenha `status = 'active'`.
- **Ativação:** é uma transação só, que aposenta a versão antiga, ativa a nova e faz `NOTIFY javai_index`.

### `ops`

| Tabela | Campos principais |
|---|---|
| `usage_daily` | PK (`day`, `model`): `requests`, `refusals`, `retracts`, `tokens_in`, `tokens_out`, `cost_usd` numeric(12,6), `latency_ms_p50`, `latency_ms_p90`, `mcp_calls` |
| `audit_log` | `id`, `at`, `actor`, `action`, `entity`, `entity_id`, `diff` (jsonb) |
| `index_job` | `id`, `index_version_id`, `trigger` (`manual` \| `agendado`), `started_at`, `finished_at`, `status`, `report` (jsonb: repositórios pulados e o motivo, como tamanho ou privado; arquivos pulados; regras do gitleaks; contagens; nunca valores) |

Nada aqui identifica um visitante. Não existe tabela de perguntas.

## SQL injection

1. **Só consultas com parâmetros:** `JdbcClient` com parâmetros nomeados, e cada SQL escrito por inteiro numa constante. Nada de concatenar string em SQL. Sem JPA: o SQL fica explícito e fácil de auditar, e o app consome menos memória.
2. **Ordenação e filtros dinâmicos** usam um enum fechado, nunca o nome de coluna que vem da requisição.
3. **O modelo nunca escreve SQL.** Não existe text-to-SQL em nenhum ponto do sistema.
4. **O Semgrep roda no CI** com as regras de SQL injection para Java, e o build quebra se houver achado.
5. **O privilégio mínimo limita o estrago:** mesmo um SQL injection no ai-api não lê `content.*` nem escreve no índice.

## Migrações

O Flyway roda no contêiner `migrate`, com o papel `javai_owner`, antes de os apps subirem.

```
V1__schemas_e_papeis.sql
V2__content.sql
V3__ai.sql
V4__ops.sql
V5__permissoes.sql
V6__rls.sql
```

Toda permissão e toda política de RLS fica nas migrações, então a segurança do banco fica versionada e revisada no Git.

## Testes que provam o acesso

Esses testes rodam com Testcontainers, num PostgreSQL 18 de verdade, a cada build:

1. `javai_ai` faz `SELECT` em `content.profile` → `permission denied`.
2. `javai_ai` lê `ai.chunk` → recebe só os trechos publicados e públicos da versão ativa, mesmo depois que o admin insere um trecho privado.
3. `javai_ai` tenta `INSERT`, `UPDATE` ou `DELETE` em `ai.chunk` → negado.
4. `javai_ai` lê `ops.audit_log` → negado.
5. Depois de um `GRANT SELECT ON content.profile TO javai_ai` feito de propósito no teste, a consulta retorna **zero linhas**.
6. Ligar um repositório com `is_fork` ou `is_private` → a constraint falha.
7. `javai_admin` tenta `UPDATE` ou `DELETE` em `ops.audit_log` → negado.
8. Uma consulta de `javai_ai` que demora mais de 2 s → cancelada.
9. Ativar uma segunda versão → a anterior vira `retired`, e há sempre no máximo uma ativa.
10. Os campos lidos pelo indexador não incluem `private_notes`.

## Backups e LGPD

- **Backup:** `pg_dump` diário, criptografado, guardado fora da VPS, e restauração testada todo mês ([01](01-arquitetura.md#deploy)).
- **Dados pessoais no banco:** só os do próprio Gilvan, cadastrados por ele.
- **Visitantes:** nada é guardado. O hash do IP vive só na memória do rate limit e muda todo dia.

## Decisões tomadas

- **Campos bilíngues (2026-10-02):** colunas `_pt` e `_en`.
- **Papéis e senhas:** o contêiner `migrate` primeiro cria e atualiza os papéis como superusuário (com as senhas vindas do ambiente, o que também serve para trocá-las) e depois aplica o Flyway como `javai_owner`. É o mesmo código que os testes usam.

## Decisões em aberto

1. **Versões antigas do índice:** quantas guardar? A proposta é 3.
