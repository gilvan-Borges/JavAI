# 03 · Contrato da API

**Status:** rascunho · **Atualizada em:** 2026-10-02

Este é o contrato entre os dois serviços e quem os consome: o portfólio, os clientes MCP e o Gilvan pelo Swagger UI. O OpenAPI 3.1 de cada app é gerado pelo springdoc e precisa bater com esta spec.

## Hosts

| Host | Dono | O que serve |
|---|---|---|
| `admin.gilvanborges.com.br` | CRUD | API do admin e Swagger UI, atrás do Cloudflare Access |
| `conteudo.gilvanborges.com.br` | CRUD | O snapshot publicado, como arquivo estático servido pelo Caddy |
| `api.gilvanborges.com.br` | IA | Chat, estatísticas e MCP (a partir da Fase 3) |

## Convenções

| Item | Regra |
|---|---|
| Versão | Prefixo `/v1`. Mudança que quebra compatibilidade vai para `/v2`; campo novo pode entrar na `/v1` |
| Formato | JSON em UTF-8. Datas em ISO 8601 |
| Campos desconhecidos | Rejeitados com 400. Um `"model": "..."` no corpo do chat é erro, não é ignorado |
| Erros | Problem Details (RFC 9457), com `application/problem+json`. Nunca trazem stack trace, SQL nem valores de linhas |

## Snapshot do conteúdo

É o único ponto de contato entre o CRUD e a IA, e também a fonte do build do portfólio.

```
GET https://conteudo.gilvanborges.com.br/v1/snapshot/pt.json
GET https://conteudo.gilvanborges.com.br/v1/snapshot/en.json
```

- **Quem serve:** o Caddy serve os arquivos de um volume que o `admin-api` grava ao publicar. O CRUD não tem rota pública.
- **Métodos:** só `GET` e `HEAD`. Qualquer outro caminho do host responde 404.
- **Cache:** `ETag` e `Last-Modified` vêm do Caddy, com `Cache-Control: public, max-age=300`. Quem consome deve usar `If-None-Match`.
- **Sem CORS:** os dois consumidores (o build do portfólio e o `ai-indexer`) leem do servidor, não do navegador.
- **O que tem:** só conteúdo publicado e público. Nada de rascunho, campo privado ou nota privada.

```json
{
  "version": 12,
  "generatedAt": "2026-10-02T06:00:00Z",
  "lang": "pt",
  "profile": {
    "fullName": "Gilvan Borges",
    "headline": "...",
    "summary": "...",
    "location": "Rio de Janeiro",
    "links": [{ "label": "GitHub", "url": "https://github.com/gilvan-Borges" }],
    "availability": null
  },
  "experiences": [
    { "company": "Vibetex", "role": "...", "startedOn": "2025-01-01", "endedOn": null,
      "description": "...", "highlights": ["..."], "stack": ["Java", "Spring Boot"] }
  ],
  "projects": [
    { "slug": "api-suporte", "name": "apiSuporte", "summary": "...", "problem": null, "solution": null,
      "results": null, "stack": ["Java 21"], "repository": "FightConnect2025/apiSuporte", "url": null, "featured": true }
  ],
  "skills": [{ "name": "RabbitMQ", "category": "mensageria", "declared": true, "notes": null }],
  "education": [],
  "certifications": [],
  "faq": [{ "question": "...", "answer": "..." }],
  "repositories": [{ "fullName": "FightConnect2025/apiSuporte", "kind": "destaque", "note": "..." }]
}
```

- **`version`:** a versão da publicação no CRUD.
- **Evidências de habilidades não estão aqui:** quem calcula é a IA, e elas aparecem nas respostas e nas ferramentas dela. O snapshot tem só o que foi cadastrado.
- **`repositories` (Fase 2):** os repositórios que o dono ligou no CRUD, com `fullName`, `kind` e `note`. É daqui que o `ai-indexer` tira a lista do que pode ler. Só entra repositório ligado que não seja fork nem privado; o `ai-indexer` ainda confere isso de novo na API do GitHub antes de clonar.

## API da IA (`api.gilvanborges.com.br`)

### CORS

- **Só nas rotas `/v1/*`.**
- **Origem permitida:** `https://gilvanborges.com.br`.
- **Métodos:** `GET`, `POST`, `OPTIONS`.
- **Cabeçalhos:** só `Content-Type`.
- **Sem credenciais.**
- **`Max-Age`:** 600.

O CORS não protege a API: um `curl` ignora CORS. Quem protege é o Turnstile, o rate limit e o teto de gasto.

### Erros

| HTTP | `type` | Quando |
|---|---|---|
| 400 | `/problems/validation` | Corpo inválido, campo desconhecido, texto fora do tamanho |
| 403 | `/problems/turnstile` | Token do Turnstile ausente, inválido, já usado ou de outro hostname |
| 403 | `/problems/origin` | `Origin` presente e fora da lista (MCP) |
| 429 | `/problems/rate-limited` | Limite por IP atingido, com `Retry-After` |
| 503 | `/problems/budget-exhausted` | Disjuntor de gasto aberto, com `Retry-After` até a meia-noite |
| 503 | `/problems/maintenance` | Chat desligado pela chave de emergência |

### Configuração do chat

O chat só atende com tudo isto configurado no `.env` da VPS; sem alguma peça, responde `503 /problems/maintenance` e o MCP segue no ar. Nenhum valor tem padrão.

| Variável | O que é |
|---|---|
| `OPENROUTER_API_KEY` | Chave do OpenRouter, usada no chat e no Jev (a API de decisão também passa pelo OpenRouter) |
| `TURNSTILE_SECRET` | Chave secreta do Turnstile |
| `HISTORY_SECRET` | Segredo-mestre (HKDF) das assinaturas do histórico, das chaves do dia e do canário |
| `JAVAI_MODELS_FAST`, `JAVAI_MODELS_STRONG` | Modelos de cada nível, o principal primeiro e os fallbacks depois |
| `JAVAI_MAX_PRICE_COMPLETION`, `JAVAI_DAILY_BUDGET_USD` | Teto de preço por milhão de tokens e teto de gasto por dia |
| `JAVAI_CHAT_ENABLED` | Chave de emergência |

### `POST /v1/chat`

**Requisição:**

```json
{
  "question": "Ele já trabalhou com RabbitMQ?",
  "lang": "pt",
  "turnstileToken": "<token do widget>",
  "history": [
    { "turnId": "01J...", "question": "Onde ele trabalha hoje?", "answer": "Na Vibetex, desde janeiro de 2025 [1].",
      "issuedAt": "2026-10-02T20:15:00-03:00", "sig": "<HMAC-SHA256 em base64url>" }
  ]
}
```

| Campo | Regra |
|---|---|
| `question` | Obrigatório, de 2 a 500 caracteres depois de normalizado (NFKC, sem caracteres de controle) |
| `lang` | `pt` ou `en`. Se ausente, o servidor detecta |
| `turnstileToken` | Obrigatório, conferido na Cloudflare, uso único, hostname do portfólio |
| `history` | No máximo 4 turnos. Turno com `sig` inválido ou com mais de 2 h é descartado |
| Corpo inteiro | No máximo 16 KB |

**Resposta:** `200` com `text/event-stream`. O portfólio lê com `fetch` e `ReadableStream`.

```
event: meta
data: {"turnId":"01J...","category":"codigo","confidence":0.93,"tier":"forte","model":"<id do modelo>"}

event: delta
data: {"text":"Sim. Ele usa RabbitMQ no apiSuporte para publicar eventos [1]."}

event: sources
data: [{"ref":1,"title":"apiSuporte: publicação de eventos","url":"https://github.com/FightConnect2025/apiSuporte/blob/<sha>/src/...#L42"}]

event: done
data: {"turn":{"turnId":"01J...","question":"...","answer":"...","issuedAt":"...","sig":"..."},"usage":{"costUsd":0.0003,"latencyMs":1400,"tokensIn":900,"tokensOut":80}}
```

| Evento | O que significa | O que o portfólio faz |
|---|---|---|
| `meta` | Categoria, nível e modelo escolhidos | Mostra "modelo · categoria · custo · tempo" |
| `trace` | Uma etapa do pipeline, com dados reais: `classify` (categoria, confiança, ms), `search` (modo `hybrid` ou `bm25`, acertos, modelo de embeddings, ms), `route` (nível e modelos) e `guard` (verificações da guarda de saída). Vem antes do `meta` e do texto | Mostra o caminho da pergunta, como um terminal. Evento desconhecido deve ser ignorado |
| `delta` | Pedaço do texto | Acrescenta com `textContent` |
| `sources` | As fontes citadas como `[n]` | Transforma cada `[n]` em link. Só o servidor escreve URLs |
| `done` | Turno assinado e uso | Guarda o turno para o `history` |
| `refusal` | Recusa antes de gerar texto | Mostra a mensagem |
| `retract` | A guarda de saída disparou no meio do texto | Apaga o que mostrou e exibe a mensagem |
| `error` | Falha no meio do stream | Mostra um erro genérico |

**Regras do conteúdo:**
- O texto vem puro, sem HTML nem markdown.
- Cada `[n]` precisa existir em `sources`.
- Todo link aponta para um destes domínios:
  - `gilvanborges.com.br`;
  - `github.com/gilvan-Borges`;
  - `github.com/FightConnect2025`;
  - `linkedin.com/in/gilvanborges`.

### `GET /v1/stats` e `GET /health`

- **`/v1/stats`:** números agregados dos últimos 30 dias (perguntas, recusas, custo médio, latência p50 e p90, respostas por modelo, chamadas MCP), com `Cache-Control: public, max-age=3600`.
- **`/health`:** responde `200 {"status":"ok"}`.

### Servidor MCP (`POST /mcp`)

- **Transporte:** Streamable HTTP sem estado, com o SDK oficial para Java. `GET /mcp` responde 405.
- **Origem:** `Origin` presente e fora da lista → 403. A ausência do cabeçalho é aceita.
- **Sem CORS.**
- **Rate limit:** 60 chamadas por minuto por IP, com `429` e `Retry-After`. O IP vem do `X-Real-IP`, que o Caddy escreve a partir do IP real da Cloudflare.
- **Corpo:** no máximo 64 KB (`413` acima disso). O `Accept` precisa listar `application/json` e `text/event-stream`.
- **Origem padrão aceita:** `https://gilvanborges.com.br` (configurável em `AIAPI_ALLOWED_ORIGINS`).
- **Sem índice ainda:** as ferramentas respondem `isError` com "the index is not available yet", em vez de falhar.
- **Ferramentas:** todas só de leitura, com `lang` (`en`, o padrão, ou `pt`), texto mais `structuredContent` e `outputSchema` declarado.

| Ferramenta | Entrada | Devolve |
|---|---|---|
| `get_profile` | nenhuma | Perfil público e links (portfólio, GitHub, LinkedIn) |
| `list_projects` | nenhuma | Projetos com status, stack e slug |
| `get_project` | `slug` | Projeto completo. Slug desconhecido gera `isError` com a lista dos válidos |
| `list_experience` | nenhuma | Experiências em ordem |
| `get_skills` | `skill` (opcional) | Habilidades com evidências |
| `find_evidence` | `technology` (de 2 a 80 caracteres) | Evidências, ou `found: false` |
| `list_repositories` | nenhuma | Repositórios indexados, com tipo, nota e stack |
| `get_repository` | `full_name` | Resumo, linguagens, stack dos manifestos e trecho do README. Só repositórios indexados |
| `search` | `query` (de 2 a 200 caracteres) | Até 5 trechos da busca híbrida, com URL |

| Prompt | Argumentos | O que faz |
|---|---|---|
| `pitch_for_role` | `role`, `lang` | Apresentação honesta para uma vaga, só com fatos das ferramentas |
| `match_job_description` | `job_description` (até 8.000 caracteres), `lang` | Requisito por requisito: evidência ou "não encontrado" |
| `explain_project` | `slug`, `lang` | Resumo de um projeto em cinco linhas |

## API do admin (`admin.gilvanborges.com.br`)

### Autenticação

O Cloudflare Access fica na frente e só deixa passar o e-mail do Gilvan, com 2FA. Dentro do app, o Spring Security valida o JWT que chega em `Cf-Access-Jwt-Assertion`:
- **Assinatura:** com as chaves de `https://<time>.cloudflareaccess.com/cdn-cgi/access/certs`.
- **`iss`:** o domínio do time no Access.
- **`aud`:** a tag AUD da aplicação no Access.
- **`email`:** igual ao do dono, que fica na configuração.

JWT ausente ou inválido resulta em 401, mesmo que a requisição tenha chegado à origem. O JWT só é aceito nesse cabeçalho; no `Authorization` ele não vale.

**Proteção contra CSRF:** o Access injeta o JWT a partir de um cookie, então o navegador manda a credencial sozinho. Em todo método que altera dados:
- se `Sec-Fetch-Site` vier diferente de `same-origin` ou `none` → 403 (`/problems/cross-site`);
- se houver corpo com `Content-Type` diferente de `application/json` → 415 (`/problems/unsupported-media-type`).

O CORS do admin é fechado, e o cookie do Access usa `SameSite=Strict`.

### Rotas

| Método e rota | O que faz |
|---|---|
| `GET` / `PUT /admin/v1/profile` | Ler e salvar o perfil |
| `PATCH /admin/v1/profile/status` | `{"status":"draft"\|"published"}` |
| `GET` / `POST /admin/v1/{experiences,projects,skills,education,certifications,faqs}` | Listar e criar (sempre como rascunho) |
| `GET` / `PUT` / `DELETE /admin/v1/{recurso}/{id}` | Ler, salvar e apagar |
| `PATCH /admin/v1/{recurso}/{id}/status` | Publicar ou voltar para rascunho |
| `POST /admin/v1/publish` | Inicia a publicação e devolve `202 {"jobId":"..."}` |
| `GET /admin/v1/jobs/{id}` | Status e relatório do job |
| `GET /admin/v1/versions` | Versões publicadas |
| `POST /admin/v1/versions/{id}/activate` | Rollback para uma versão guardada |
| `GET /admin/v1/audit?from=&to=` | Auditoria (padrão: últimos 7 dias, até 500 linhas) |
| `GET /admin/v1/repos`, `POST /admin/v1/repos/sync`, `PUT /admin/v1/repos/{owner}/{name}` | Repositórios ligados (Fase 2) |

Toda escrita vira uma linha em `ops.audit_log`, com o e-mail do JWT e o diff.

**Swagger UI e OpenAPI:** em `/admin/swagger-ui` e `/admin/v3/api-docs`, só atrás do Access.

### Erros do admin

| HTTP | `type` | Quando |
|---|---|---|
| 400 | `/problems/validation` | Campo inválido (com a lista `errors`), JSON malformado ou campo desconhecido |
| 403 | `/problems/cross-site` | Requisição de outro site |
| 404 | `/problems/not-found` | Registro inexistente |
| 409 | `/problems/conflict` | Valor único repetido (por exemplo, o slug) |
| 409 | `/problems/publish-in-progress` | Já existe uma publicação rodando |
| 415 | `/problems/unsupported-media-type` | Corpo que não é JSON |
| 422 | `/problems/constraint` | Uma regra do banco recusou os dados. Traz o nome da `constraint` |
| 422 | `/problems/publish-rejected` | A publicação foi recusada (por exemplo, perfil não publicado) |
| 422 | `/problems/invalid-operation` | Versão que não pode ser ativada |

## Decisões em aberto

1. **Resultados da bateria:** a proposta é um arquivo JSON publicado pelo CI, que o portfólio lê no build ([05](05-avaliacao.md#resultados)).
