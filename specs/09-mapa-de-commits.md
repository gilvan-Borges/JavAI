# 09 · O mapa de commits

**Status:** aprovada (backend implementado; a página do portfólio, a "galáxia de commits", é a etapa seguinte) · **Atualizada em:** 2026-10-04

## Por que existe

O portfólio quer desenhar o histórico de commits dos repositórios públicos como uma galáxia: um sistema por repositório, um ponto por commit. Os dados já passam pelo indexador (ele clona cada repositório de forma rasa para montar o dossiê da spec 08), então o mapa sai do mesmo clone, sem chamada extra à API do GitHub.

## O que é gravado, e o que nunca é

| Gravado | Nunca gravado |
|---|---|
| Por commit: o hash curto (7 caracteres), o instante e a **primeira linha** da mensagem (até 100 caracteres, sem caracteres de controle) | Nome ou e-mail do autor |
| O nome completo do repositório e o link do commit | O corpo da mensagem, o diff, os arquivos |
| | Qualquer coisa de repositório privado, fork, vazio ou que deu erro |

**Regra de privacidade, em ordem:**

1. **Só público e que não é fork.** O `GitHubRepoSource` pergunta à API do GitHub antes de clonar; repositório privado (ou sem acesso, 404), fork, grande demais, vazio ou com erro vira "pulado" e não produz nenhum commit. Os testes provam cada um desses casos.
2. **Sem dado de autor.** O `RepoRead` leva só `(sha7, instante, assunto)`; o autor nem sai do clone.
3. **Mensagem que parece segredo some.** Se o `SecretScanner` achar algo no dossiê (os 10 commits mais recentes) **ou** em qualquer assunto do mapa (até 150), nenhuma mensagem daquele repositório é gravada: o assunto de todos os commits vira `(mensagem omitida)`. Hashes e datas continuam.
4. **O banco é o último limite.** `index.repo_commit` tem `CHECK` no hash (`^[0-9a-f]{7,40}$`), no nome do repositório (o mesmo de `skill_evidence`), na URL (`^https://`) e no assunto (até 200 caracteres).

## Banco (V8)

`index.repo_commit (id, version_id, repo_full_name, sha, committed_at, subject, url)`, por versão do índice, com `ON DELETE CASCADE` (o histórico não sobrevive à versão). `ai_indexer` tem `SELECT, INSERT, DELETE`; `ai_reader` só `SELECT`. RLS ligado e forçado, com as mesmas políticas de `skill_evidence`: o indexador vê tudo, o reader só a versão ativa. As linhas entram junto com os trechos, antes de a versão ser ativada; se a gravação falha, a versão é marcada como falha e a ativa continua valendo.

## Indexador

- O clone raso passou a ter profundidade `COMMIT_MAP_DEPTH = 150`. O dossiê (`REPO_FACTS.md`) continua com os `COMMIT_HISTORY = 10` mais recentes, então o texto dele e o documento de atividade não mudam.
- O relatório da execução ganha `commitsNoMapa` (total de commits gravados), ao lado de `trechosDeAtividade`.

## API

`GET /v1/commit-map`, sem parâmetros:

```json
{
  "updatedAt": "2026-10-04T12:00:00Z",
  "totals": { "repos": 38, "commits": 1450 },
  "repos": [
    { "name": "controlei", "fullName": "gilvan-Borges/controlei", "url": "https://github.com/gilvan-Borges/controlei",
      "commits": [ { "sha": "9d00098", "at": "2026-10-03T14:21:00Z", "subject": "fix(brain): one point per repository..." } ] }
  ]
}
```

| Campo | Regra |
|---|---|
| `updatedAt` | Quando a versão ativa do índice foi ativada (ou o instante da consulta, se não se souber) |
| `totals` | Repositórios com ao menos um commit e a soma dos commits devolvidos |
| `repos` | Do que tem mais commits para o que tem menos; empate pelo nome |
| `commits` | Do mais novo para o mais antigo, no máximo **150** por repositório (o teto vale também no SQL) |

- Sem índice ativo, **503** com Problem Details (`/problems/maintenance`, `Retry-After: 60`), como o `/v1/brain`.
- Resposta com `Cache-Control: public, max-age=600`. O corpo montado fica em memória por 10 minutos e é refeito na hora quando a versão ativa muda.
- CORS: o das rotas `/v1/**` (só o portfólio, `GET`). O Caddy roteia `/v1/commit-map` para o ai-api, junto de `/v1/brain`.
- Nada além das colunas do contrato sai da API: o `JdbcCommitMapStore` seleciona só repositório, hash, instante e assunto.

## Decisões

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| Aproveitar o clone raso do dossiê (profundidade 150) | Chamar a API do GitHub por repositório | O limite sem token é de 60 por hora; o clone já existia |
| Omitir **todas** as mensagens do repositório quando uma parece segredo | Omitir só a mensagem suspeita | O detector é heurístico: confiar no resto do repositório depois de um achado é apostar |
| Gravar na versão do índice | Tabela fora do índice, com ciclo próprio | Herda RLS, ativação atômica, rollback e limpeza das versões antigas |
| Teto de 150 commits por repositório | Histórico inteiro | Mantém o clone, o banco e o JSON pequenos; a galáxia não precisa de mais |

## Pendências

- A **página do portfólio** que desenha a galáxia.
- Repositórios com mais de 150 commits mostram só os mais recentes; se a galáxia pedir mais, o teto é uma constante (`COMMIT_MAP_DEPTH`) e o custo é o tamanho do clone.
