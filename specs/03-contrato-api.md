# 03 · Contrato da API

**Status:** rascunho · **Atualizada em:** 2026-10-02

Este é o contrato entre o JavAI e quem o consome: o portfólio, os clientes MCP e o próprio Gilvan pelo Swagger UI. O OpenAPI 3.1 é gerado pelo springdoc a partir do código e precisa bater com esta spec. Um teste de contrato compara os dois.

## Convenções

| Item | Regra |
|---|---|
| Hosts | Público: `https://api.gilvanborges.com.br`. Admin: `https://admin.gilvanborges.com.br` |
| Versão | Prefixo `/v1`. Mudanças que quebram compatibilidade vão para `/v2`; campos novos podem entrar na `/v1` |
| Formato | JSON em UTF-8. Datas em ISO 8601 com fuso |
| Campos desconhecidos | Rejeitados com 400. Um `"model": "..."` no corpo do chat não é ignorado: é erro |
| Erros | Problem Details (RFC 9457), com `application/problem+json` |
| OpenAPI público | `GET /v1/openapi.json` |
| OpenAPI do admin | Só atrás do Access, em `/admin/v3/api-docs`. O Swagger UI fica em `/admin/swagger-ui` |

### Erros

| HTTP | `type` | Quando |
|---|---|---|
| 400 | `/problems/validation` | Corpo inválido, campo desconhecido, texto fora do tamanho |
| 403 | `/problems/turnstile` | Token do Turnstile ausente, inválido, já usado ou de outro hostname |
| 403 | `/problems/origin` | `Origin` presente e fora da lista permitida (MCP) |
| 429 | `/problems/rate-limited` | Limite por IP atingido. Vem com `Retry-After` |
| 503 | `/problems/budget-exhausted` | Disjuntor de gasto aberto. Vem com `Retry-After` até a meia-noite |
| 503 | `/problems/maintenance` | Chat desligado pela chave de emergência |

O corpo do erro nunca traz stack trace, SQL nem o nome de classes internas.

## API pública (`api.gilvanborges.com.br`)

### CORS

- **Só nas rotas `/v1/*`.**
- **Origens permitidas:** `https://gilvanborges.com.br` (e `https://www.gilvanborges.com.br`, se existir).
- **Métodos:** `GET`, `POST`, `OPTIONS`.
- **Cabeçalhos:** só `Content-Type`.
- **Sem credenciais e sem cookies.**
- **`Access-Control-Max-Age`:** 600.

O CORS não é uma barreira de segurança, porque um `curl` ignora CORS. Quem protege o chat é o Turnstile, o rate limit e o teto de gasto.

### `POST /v1/chat`

**Requisição:**

```json
{
  "question": "Ele já trabalhou com RabbitMQ?",
  "lang": "pt",
  "turnstileToken": "<token do widget>",
  "history": [
    {
      "turnId": "01J...",
      "question": "Onde ele trabalha hoje?",
      "answer": "Na Vibetex, desde janeiro de 2025 [1].",
      "issuedAt": "2026-10-02T20:15:00-03:00",
      "sig": "<HMAC-SHA256 em base64url>"
    }
  ]
}
```

| Campo | Regra |
|---|---|
| `question` | Obrigatório, de 2 a 500 caracteres depois de normalizado (NFKC, sem caracteres de controle) |
| `lang` | `pt` ou `en`. Se ausente, o servidor detecta o idioma |
| `turnstileToken` | Obrigatório. É conferido na Cloudflare, vale uma vez só e precisa ter o hostname do portfólio |
| `history` | No máximo 4 turnos. Turno com `sig` inválido ou com mais de 2 h é descartado em silêncio |
| Corpo inteiro | No máximo 16 KB |

**Resposta:** `200`, com `Content-Type: text/event-stream`. O portfólio lê com `fetch` e `ReadableStream`, porque o `EventSource` não aceita POST.

```
event: meta
data: {"turnId":"01J...","category":"codigo","confidence":0.93,"tier":"forte","model":"<id do modelo>"}

event: delta
data: {"text":"Sim. Ele usa RabbitMQ no "}

event: delta
data: {"text":"apiSuporte para publicar eventos [1]."}

event: sources
data: [{"ref":1,"title":"apiSuporte: publicação de eventos","url":"https://github.com/FightConnect2025/apiSuporte/blob/<sha>/src/...#L42"}]

event: done
data: {"turn":{"turnId":"01J...","question":"...","answer":"...","issuedAt":"...","sig":"..."},"usage":{"costUsd":0.0003,"latencyMs":1400,"tokensIn":900,"tokensOut":80}}
```

| Evento | O que significa | O que o portfólio faz |
|---|---|---|
| `meta` | Categoria, nível e modelo escolhidos pelo roteador | Mostra a linha "modelo · categoria · custo · tempo" |
| `delta` | Pedaço do texto | Acrescenta com `textContent` |
| `sources` | As fontes citadas como `[n]` | Transforma cada `[n]` em link. Só o servidor escreve URLs |
| `done` | O turno assinado e o uso | Guarda o turno para mandar no `history` da próxima pergunta |
| `refusal` | Recusa antes de gerar texto: `{"reason":"fora_do_escopo","message":"..."}` | Mostra a mensagem |
| `retract` | A guarda de saída disparou no meio do texto: `{"message":"..."}` | Apaga o que já mostrou e exibe a mensagem |
| `error` | Falha no meio do stream: `{"code":"upstream_unavailable"}` | Mostra um erro genérico |

**Regras do conteúdo:**
- O texto vem puro, sem HTML nem markdown.
- Cada `[n]` precisa existir em `sources`. Se o modelo citar uma fonte que não existe, o servidor tira a citação.
- Todo link em `sources` aponta para um destes domínios:
  - `gilvanborges.com.br`;
  - `github.com/gilvan-Borges`;
  - `github.com/FightConnect2025`;
  - `linkedin.com/in/gilvanborges`.

### `GET /v1/content/snapshot?lang=pt|en`

Devolve o conteúdo publicado, para o build do portfólio. Responde com `ETag` e `Cache-Control: public, max-age=300`.

```json
{
  "indexVersion": 12,
  "generatedAt": "2026-10-02T03:00:00-03:00",
  "profile": { "fullName": "...", "headline": "...", "summary": "...", "location": "...", "links": [] },
  "experiences": [],
  "projects": [],
  "skills": [
    { "name": "Spring Security", "declared": true, "evidence": [ { "repo": "...", "path": "...", "url": "..." } ] }
  ],
  "education": [],
  "certifications": [],
  "faq": []
}
```

Só traz o que é publicado e público. O ai-api lê de `ai.content_snapshot` e nunca toca em `content.*`.

### `GET /v1/stats`

Mostra os números agregados dos últimos 30 dias:
- perguntas e recusas;
- custo médio;
- latência p50 e p90;
- respostas por modelo;
- chamadas ao MCP.

Usa `Cache-Control: public, max-age=3600`.

### `GET /health`

Responde `200 {"status":"ok"}`, sem detalhes.

## Servidor MCP (`POST /mcp`)

- **Transporte:** Streamable HTTP sem estado, com o SDK oficial de MCP para Java.
- **`GET /mcp`:** responde 405.
- **Origem:** se o cabeçalho `Origin` vier e estiver fora da lista permitida, a resposta é 403, como a spec do MCP exige. Clientes rodando em servidor costumam não mandar `Origin`, e isso é aceito.
- **Sem CORS:** o MCP não é chamado por páginas web.
- **Rate limit:** 60 chamadas por minuto por IP.
- **Ferramentas:** todas com `readOnlyHint: true`, `idempotentHint: true` e `openWorldHint: false`.
- **Idioma:** todas aceitam `lang` (`en`, que é o padrão, ou `pt`).
- **Formato da resposta:** texto mais `structuredContent`, com `outputSchema` declarado.

| Ferramenta | Entrada | Devolve |
|---|---|---|
| `get_profile` | nenhuma | Perfil público e links |
| `list_projects` | nenhuma | Projetos com status, stack e slug |
| `get_project` | `slug` | O projeto completo. Slug desconhecido gera `isError` com a lista dos válidos |
| `list_experience` | nenhuma | Experiências em ordem |
| `get_skills` | `skill` (opcional) | Habilidades com evidências (repositório, arquivo, URL) |
| `find_evidence` | `technology` (de 2 a 80 caracteres) | Evidências encontradas, ou `found: false` |
| `list_repositories` | nenhuma | Repositórios ligados, com tipo, nota e stack |
| `get_repository` | `full_name` | Resumo, linguagens, stack tirada dos manifestos e trecho do README. Só aceita repositórios ligados |
| `search` | `query` (de 2 a 200 caracteres) | Até 5 trechos da busca híbrida, com URL |

| Prompt | Argumentos | O que faz |
|---|---|---|
| `pitch_for_role` | `role`, `lang` | Apresentação curta e honesta para uma vaga, só com fatos das ferramentas |
| `match_job_description` | `job_description` (até 8.000 caracteres), `lang` | Requisito por requisito: evidência ou "não encontrado" |
| `explain_project` | `slug`, `lang` | Resumo de um projeto em cinco linhas |

Os prompts mandam o modelo usar só o que as ferramentas devolvem e dizer quando algo não existe.

## API do admin (`admin.gilvanborges.com.br`)

### Autenticação

O Cloudflare Access fica na frente e só deixa passar o e-mail do Gilvan, com 2FA. Dentro do app, o Spring Security valida o JWT que vem em `Cf-Access-Jwt-Assertion`:
- **Assinatura:** com as chaves de `https://<time>.cloudflareaccess.com/cdn-cgi/access/certs`.
- **`iss`:** o domínio do time no Access.
- **`aud`:** a tag AUD da aplicação no Access.
- **`email`:** igual ao do dono, que fica na configuração.

Se o JWT estiver ausente ou for inválido, a resposta é 401, mesmo que a requisição tenha chegado à origem.

**Proteção contra CSRF:**
- O admin só aceita `Content-Type: application/json`, o que força o preflight do navegador.
- O CORS do admin é fechado.
- O cookie do Access usa `SameSite=Strict`.

### Rotas

| Método e rota | O que faz |
|---|---|
| `GET` / `PUT /admin/v1/profile` | Ler e salvar o perfil |
| `GET` / `POST /admin/v1/{experiences,projects,skills,education,certifications,faqs}` | Listar e criar |
| `GET` / `PUT` / `DELETE /admin/v1/{recurso}/{id}` | Ler, salvar e apagar |
| `PATCH /admin/v1/{recurso}/{id}/status` | `{"status":"draft"\|"published"}` |
| `GET /admin/v1/repos` | Repositórios da conta e da organização, junto com o que está configurado |
| `POST /admin/v1/repos/sync` | Atualiza a lista a partir do GitHub |
| `PUT /admin/v1/repos/{owner}/{name}` | `{"enabled":true,"kind":"destaque","note":"..."}`. Fork ou privado gera 400 |
| `POST /admin/v1/publish` | Inicia o indexador e devolve `202 {"jobId":"..."}` |
| `GET /admin/v1/jobs/{id}` | Status e relatório do job |
| `GET /admin/v1/index-versions` | Versões do índice |
| `POST /admin/v1/index-versions/{id}/activate` | Rollback para uma versão guardada |
| `POST /admin/v1/chat/disable` e `/enable` | Chave de emergência do chat |
| `GET /admin/v1/audit?from=&to=` | Auditoria |

Toda escrita vira uma linha em `ops.audit_log`, com o e-mail do JWT e o diff.

## Decisões em aberto

1. **Resultados da bateria:** um endpoint próprio (`/v1/evals/latest`) ou um arquivo JSON publicado pelo CI, que o portfólio lê no build? A proposta é o arquivo ([05](05-avaliacao.md#resultados)).
2. **`www.gilvanborges.com.br`:** existe? Se não, sai da lista de CORS.
