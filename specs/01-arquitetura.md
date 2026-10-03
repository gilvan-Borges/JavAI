# 01 · Arquitetura

**Status:** rascunho · **Atualizada em:** 2026-10-02

## Visão geral

O repositório tem dois serviços isolados: o **CRUD**, onde o Gilvan cadastra o conteúdo, e a **IA**, que responde perguntas. Eles não compartilham código, banco nem rede de dados. A única ligação entre os dois é um snapshot público, lido por HTTPS, o mesmo que o portfólio lê.

```
┌──────────────────────────── CRUD (admin/) ────────────────────────────┐
│  admin.gilvanborges.com.br (Cloudflare Access: só o Gilvan)           │
│     └─► admin-api ──► postgres-admin   (rede admin-data, interna)     │
│                  └─► grava o snapshot em arquivo ao publicar          │
│  conteudo.gilvanborges.com.br ─► Caddy serve o arquivo (só GET)       │
└────────────────────────────────┬──────────────────────────────────────┘
                                 │ HTTPS público, só leitura
              ┌──────────────────┴──────────────────┐
              ▼                                     ▼
   Portfólio (build na Cloudflare)    ┌──────────── IA (ai/) ─────────────┐
                                      │  ai-indexer: snapshot + GitHub    │
                                      │     └─► postgres-ai (rede ai-data)│
                                      │  ai-api: /v1/chat e /mcp          │
                                      │  api.gilvanborges.com.br          │
                                      └───────────────────────────────────┘
```

## A regra de isolamento

| | CRUD | IA |
|---|---|---|
| Código | `admin/` | `ai/` |
| Banco | contêiner `postgres-admin`, banco `admin` | contêiner `postgres-ai`, banco `ai` |
| Rede de dados | `admin-data` (interna) | `ai-data` (interna) |
| Host público | `admin.` (atrás do Access) e `conteudo.` (arquivo estático) | `api.` |
| O que escreve | conteúdo, publicações, auditoria | índice, contadores de uso |
| O que lê do outro | nada | o snapshot público, por HTTPS, como qualquer visitante |

O que os dois compartilham:
- o POM pai, que só define versões e plugins;
- o `docker-compose` e o Caddy, na pasta `infra/`;
- o contrato do snapshot ([03](03-contrato-api.md#snapshot-do-conteúdo)).

**O CRUD não sabe que a IA existe.** Ele publica o snapshot e avisa o portfólio. A IA confere o snapshot sozinha.

**Como a regra é garantida:**
- Um contêiner da IA não tem rota de rede para o `postgres-admin`, nem credencial para ele.
- O Maven Enforcer de cada lado proíbe dependência de módulos do outro lado (`admin-*` × `ai-*`). O build quebra se alguém tentar.
- A regra "a IA não vaza o que não consegue ler" deixa de depender de permissão de banco e vira isolamento físico. Rascunhos, campos privados e auditoria ficam num banco que a IA não alcança.

## Módulos

| Módulo | Lado | O que contém | Fase |
|---|---|---|---|
| `admin/admin-api` | CRUD | App Spring Boot: CRUD, publicação, auditoria, Swagger UI. O domínio (snapshot e caso de uso de publicação) fica no pacote `domain`, sem Spring, conferido por ArchUnit | 1 |
| `admin/admin-migrations` | CRUD | Bootstrap dos papéis e Flyway do banco `admin` | 1 |
| `ai/ai-core` | IA | Java puro: busca híbrida, guardas, roteador, montagem do prompt, assinatura do histórico | 2 |
| `ai/ai-indexer` | IA | Worker privado: lê o snapshot e o GitHub, gera trechos, evidências e embeddings | 2 |
| `ai/ai-api` | IA | App público: `/v1/chat`, `/v1/stats`, `/mcp` | 3 e 4 |
| `ai/ai-migrations` | IA | Bootstrap e Flyway do banco `ai` | 2 |
| `ai/evals` | IA | Bateria de avaliação em JUnit 5, contra uma instância real via HTTP | 4 |

**Versões** (estáveis em 2 de outubro de 2026): Java 25 (LTS), Spring Boot 4.1, SDK oficial de MCP para Java 2.0, PostgreSQL 18.

## Lado CRUD

### Publicação

`POST /admin/v1/publish` cria um job, que roda numa virtual thread:

1. **Cria uma versão** em `publish.version`, com status `building`.
2. **Lê o conteúdo publicado e público** numa transação só leitura, com isolamento `REPEATABLE READ`, usando uma lista explícita de colunas. `private_notes` nunca é selecionado.
3. **Monta um snapshot por idioma** (pt e en) e grava cada um em `publish.snapshot`.
4. **Ativa a versão** e exporta os arquivos públicos:
   - os dois arquivos são gravados primeiro em temporários;
   - a ativação no banco acontece numa transação;
   - depois os arquivos são trocados com `ATOMIC_MOVE`;
   - se a gravação dos arquivos falhar, a transação desfaz a ativação.
5. **Avisa o portfólio** pelo deploy hook. Se o aviso falhar, a ativação não é desfeita.

**Rollback:** `POST /admin/v1/versions/{id}/activate` reativa uma versão guardada e reexporta os arquivos dela. O banco guarda as 3 últimas versões aposentadas.

**Os arquivos públicos:**
- ficam num volume Docker que o admin-api grava e o Caddy monta só para leitura;
- o Caddy serve em `https://conteudo.gilvanborges.com.br/v1/snapshot/{pt,en}.json` e cuida de `ETag` e `Last-Modified`;
- o CRUD não tem nenhuma rota pública.

### Segurança do admin

Em [03](03-contrato-api.md#api-do-admin-admingilvanborgescombr): Cloudflare Access na frente, validação do JWT no app, proteção contra CSRF por Fetch Metadata e exigência de JSON.

## Lado IA

### Como a IA fica sabendo de conteúdo novo

A cada 5 minutos o `ai-indexer` faz duas conferências baratas, as duas com GET condicional (`If-None-Match`), e um 304 não gasta o limite da API do GitHub:

1. **O snapshot do CRUD.** Se vier um snapshot novo, reindexa.
2. **O HEAD de cada repositório ligado** (`GET /repos/{dono}/{nome}/commits/HEAD`, sem token). O commit lido na última indexação fica guardado junto com os ETags da versão ativa; se o HEAD for outro, reindexa. Assim um `git push` num repositório aparece na IA em até uns 35 minutos, sem webhook.

Um repositório que recebe commit a toda hora não derruba o indexador: a reindexação disparada por commit só acontece a cada `INDEXER_REPO_MIN_INTERVAL` (30 min por padrão). Se a conferência falhar (rede, limite da API), o repositório conta como "não mudou" e a próxima volta tenta de novo.

Todo dia às 03:00 (horário de São Paulo) ele reindexa tudo de qualquer jeito, como rede de segurança. Sem webhook, o CRUD continua sem saber que a IA existe.

### Indexador (`ai-indexer`)

1. **Cria uma versão** em `index.version`, com status `building`.
2. **Lê o snapshot** em pt e en. Tudo o que está nele já é publicado e público, porque o CRUD só exporta isso.
3. **Lê os repositórios** listados no snapshot (`repositories`, ligados pelo Gilvan no CRUD). Para cada um:
   - **Confere de novo os metadados pela API do GitHub:** `fork`, `private`, `size`. Se algum mudou, o repositório é pulado: a IA não confia cegamente no CRUD.
   - **Limite de tamanho:** se o `size` passar de `javai.indexer.max-repo-size` (50 MB por padrão), o repositório não é clonado e o relatório registra o motivo. O `size` conta o repositório inteiro, com histórico, então serve como teto.
   - **Clone:** `git clone --depth 1` do branch padrão, por HTTPS, com JGit e sem token, numa pasta temporária apagada no fim.
   - **Filtro de arquivos** (lista abaixo).
   - **gitleaks na pasta:** arquivo com achado é pulado, e o relatório registra só o caminho e a regra, nunca o valor.
4. **Calcula as evidências de habilidades,** sem LLM (tabela abaixo).
5. **Monta os trechos:**
   - README dividido por seção;
   - código por arquivo, com cabeçalho de repositório, caminho, pacote e classe;
   - arquivo grande é dividido;
   - cada trecho recebe um título em terceira pessoa.
6. **Gera os embeddings.**
7. **Ativa a versão** e faz `NOTIFY ai_index`. O `ai-api` recarrega o índice em memória: na prática ele confere o id da versão ativa a cada 30 s (mais simples e mais robusto que manter uma conexão de LISTEN), e o `NOTIFY` fica como sinal para quem quiser ouvir.

**Arquivos que entram:**
- `.java`, `.kt`, `.ts`, `.dart`, `.cs`, `.md`;
- `pom.xml`, `build.gradle(.kts)`, `package.json`, `pubspec.yaml`, `*.csproj`, `Dockerfile`;
- `docker-compose*.yml`, `compose*.yaml`;
- `.github/workflows/*.yml`;
- `db/migration/*.sql`.

**Arquivos que nunca entram:**
- pastas `node_modules`, `target`, `build`, `dist`, `.angular`, `.mvn`, `vendor`;
- lockfiles e binários;
- `application*.properties` e `application*.yml`;
- `.env*`;
- arquivos com mais de 100 KB.

**Evidências de habilidades** (sem LLM):

| Sinal no repositório | Habilidade |
|---|---|
| `spring-boot-starter-security` no `pom.xml` | Spring Security |
| `org.flywaydb` | Flyway |
| `org.testcontainers` | Testcontainers |
| `spring-boot-starter-amqp` ou `spring-rabbit` | RabbitMQ |
| `org.postgresql:postgresql` | PostgreSQL |
| `spring-boot-starter-data-mongodb` | MongoDB |
| `@angular/core` no `package.json` | Angular |
| `Dockerfile` ou arquivo compose | Docker |
| `.github/workflows/*.yml` | GitHub Actions |
| `*Test.java` importando `org.junit.jupiter` | JUnit 5 |

- **Onde fica o mapa:** num YAML versionado.
- **O que cada evidência aponta:** repositório, arquivo, linha e URL.
- **Habilidade sem evidência:** se ela foi declarada no CRUD, aparece como "declarada, sem evidência pública".

### Fluxo de uma pergunta no chat (`ai-api`)

```
 1. Validação         tamanho, idioma, campos desconhecidos              → 400
 2. Turnstile         siteverify na Cloudflare, uso único, hostname      → 403
 3. Limites           por IP (hash) e teto diário de gasto               → 429 / 503
 4. Histórico         descarta turnos sem assinatura válida ou vencidos
 5. Em paralelo (virtual threads):
      a. o Jev classifica a pergunta (categoria + probabilidade)
      b. busca híbrida no índice em memória
 6. Decisão           ataque ou fora do escopo → recusa fixa, sem chamar modelo
 7. Prompt            instruções + trechos em blocos delimitados + pergunta
 8. OpenRouter        modelo do nível escolhido, streaming, max_tokens
 9. Guarda de saída   retém o fim do texto; se aparecer URL, HTML ou canário → retract
10. Fim               fontes, turno assinado, meta (modelo, categoria, custo, tempo)
11. Contadores        só valores agregados, em ops.usage_daily
```

Se o visitante fecha a conexão, a chamada ao OpenRouter é cancelada. O contexto da requisição é carregado com Scoped Values.

**Cliente do OpenRouter próprio,** feito com `java.net.http.HttpClient`, por dois motivos: o JavAI usa campos que só o OpenRouter tem (`models`, `provider`, `max_price`), e a guarda de saída precisa controlar o streaming.

### Roteador

| Categoria do Jev | Ação | Nível do modelo |
|---|---|---|
| `perfil` | responde | `rapido` |
| `codigo` | responde | `forte` |
| `vaga` | responde | `forte` |
| `fora_do_escopo` (p ≥ 0,6) | recusa sem chamar modelo | nenhum |
| `ataque` (p ≥ 0,5) | recusa sem chamar modelo | nenhum |

- **Os limiares são o ponto de partida.** A bateria de [05](05-avaliacao.md) calibra os valores, com a regra de que nenhuma pergunta legítima pode ser barrada.
- **Os modelos vêm da configuração do servidor** (`javai.models.rapido`, `javai.models.forte`), cada um com fallback. A requisição não tem campo de modelo.
- **Um modelo só entra na lista depois de passar na bateria.**

Parâmetros enviados em toda chamada ao OpenRouter:

```json
{
  "models": ["<principal>", "<fallback>"],
  "stream": true,
  "max_tokens": 300,
  "provider": { "data_collection": "deny", "zdr": true, "max_price": { "prompt": 0, "completion": 0 } }
}
```

Os valores de `max_price` são definidos junto com os modelos, na Fase 4. Se o Jev falhar (erro, cota ou mais de 800 ms), a pergunta segue com o nível `rapido` e as outras camadas continuam valendo.

### Busca

- **Índice em memória.** O índice ativo é carregado na memória do `ai-api` quando ele sobe e a cada `NOTIFY ai_index`. O novo índice é montado ao lado e trocado de uma vez (`AtomicReference`).
- **BM25 implementado no `ai-core`.** O texto é normalizado com NFKC, minúsculas e acentos removidos só para comparar.
- **Cosseno sobre embeddings normalizados.** A interface `VectorOps` tem duas implementações:
  - escalar;
  - Vector API, que ainda é incubadora.

  O benchmark JMH entre as duas fica no repositório.
- **RRF (k = 60)** junta as duas listas, e os 5 primeiros trechos vão para o modelo.
- **Títulos em terceira pessoa** em cada trecho, para aproximar as perguntas do conteúdo.
- **O rerank com o Jev só entra se a avaliação da busca mostrar ganho.**

### Embeddings

- **Modelo candidato:** `multilingual-e5-small`, com 384 dimensões e suporte a português e inglês. Roda com o ONNX Runtime para Java e o tokenizer da DJL, com os prefixos `query: ` e `passage: `.
- **Versão: int8 (decidido em 2026-10-03).** O `QuantizationQualityTest` compara a versão int8 (118 MB) com a fp32 (470 MB) nos mesmos textos: cosseno médio de 0,9967 (mínimo 0,9947) e o mesmo primeiro lugar em 8 de 8 perguntas. Não há perda que justifique 4 vezes mais memória. A VPS tem AVX512-VNNI, o conjunto de instruções para o qual esta versão foi feita.
- **Arquivos fixados:** os dois arquivos do modelo vêm de um commit específico do repositório (`614241f6…`) e têm SHA-256 conferido no código (`ModelStore`). Se o hash não bater, o app não sobe e o arquivo baixado é descartado.
- **Bibliotecas nativas dentro dos jars:** o ONNX Runtime e o tokenizador da DJL trazem os binários para Linux, macOS e Windows no próprio jar, então nada é baixado em tempo de execução.
- **Verificação do arquivo:** o modelo é baixado no build da imagem e conferido por SHA-256.
- **Memória:** a memória do ONNX Runtime é nativa e não entra no heap da JVM, por isso o limite do contêiner precisa cobrir o heap e mais o modelo.

### Guardas

O detalhe de cada ameaça está em [04](04-ameacas.md). Em resumo:

- **Entrada:**
  - limites de tamanho;
  - campos desconhecidos rejeitados;
  - tags que imitam os delimitadores do prompt são removidas.
- **Jev:** classifica como `ataque` ou `fora_do_escopo`.
- **Prompt:**
  - nada secreto;
  - um canário por versão do prompt;
  - dados dentro de `<fonte id="n">`;
  - o modelo cita `[n]` e **nunca escreve URL**.
- **Saída:**
  - o servidor retém os últimos N caracteres antes de repassar;
  - canário, URL, HTML ou imagem disparam o `retract`.
- **Histórico:**
  - HMAC-SHA256 com chave derivada por HKDF;
  - validade de 2 horas;
  - no máximo 4 turnos.

## Custos e limites

Valores iniciais propostos:

| Limite | Valor |
|---|---|
| Perguntas por IP | 3 por minuto, sem limite diário (o teto de gasto do dia continua valendo) |
| Bloqueio por ataque | 12 horas por IP, quando o Jev classifica `ataque` com confiança de 0,90 ou mais, ou depois de 3 recusas por ataque. Pergunta fora do escopo nunca bloqueia |
| Chamadas MCP por IP | 60 por minuto |
| Teto diário de gasto (disjuntor) | US$ 1,00 |
| Limite de crédito na chave do OpenRouter | US$ 10 por mês |
| `max_tokens` da resposta | 300 |
| Tamanho da pergunta | até 500 caracteres |

- **Rate limit:** balde de fichas em memória (implementação própria, sem Bucket4j: são 60 linhas e zero dependência), chaveado por `HMAC(chave do dia, IP)` no chat (Fase 4). No MCP (Fase 3) a chave é o próprio IP, só em memória: nunca é gravado nem aparece em log. A chave do dia é derivada por HKDF, então não dá para reverter o hash nem ligar o mesmo IP entre dias.
- **Disjuntor:** abre quando o gasto do dia atinge o teto e fecha à meia-noite (horário de São Paulo).

## Infraestrutura

### Serviços no docker-compose

| Serviço | Redes | Porta publicada | Memória | Fase |
|---|---|---|---|---|
| `caddy` | `edge` | 443 | 128 MB | 1 |
| `postgres-admin` | `admin-data` (interna) | nenhuma | 384 MB | 1 |
| `admin-migrate` | `admin-data` | nenhuma | roda e sai | 1 |
| `admin-api` | `edge`, `admin-data` | nenhuma | 640 MB | 1 |
| `postgres-ai` | `ai-data` (interna) | nenhuma | 384 MB | 2 |
| `ai-migrate` | `ai-data` | nenhuma | roda e sai | 2 |
| `ai-indexer` | `ai-egress`, `ai-data` | nenhuma | 1 GB | 2 |
| `ai-api` | `ai-edge`, `ai-data`, `ai-egress` | nenhuma | 512 MB | 3 e 4 |

- **Isolamento de rede:** os contêineres da IA não entram na rede `admin-data`, então o `postgres-admin` é inalcançável para eles. O `ai-indexer` também não usa a rede `edge` do CRUD: ele sai para a internet por uma rede própria, a `ai-egress`, e por isso não enxerga o `admin-api` nem o Caddy.
- **Rede do `ai-api`:** `ai-edge` (só com o Caddy), `ai-data` (só com o `postgres-ai`) e `ai-egress` (só para sair à internet: Turnstile, Jev e OpenRouter). Nenhuma das três tem contêiner do CRUD, então ele não alcança o `admin-api`. O modelo de embeddings vem do volume `ai-models`, que o `ai-indexer` baixa e verifica e o `ai-api` só lê (montado `ro`).
- **`/tmp` do `ai-indexer` e do `ai-api` com `exec`:** o ONNX Runtime e o tokenizador extraem as bibliotecas nativas do jar para o diretório temporário e as carregam de lá, e o Docker monta `tmpfs` sem permissão de execução por padrão.
- **Por que não há Redis:** o `ai-api` é uma instância só. O rate limit fica em memória e o gasto diário fica no `postgres-ai`.
- **O Docker ignora o `ufw`:** uma porta publicada fica aberta para a internet mesmo com o firewall bloqueando. Por isso só o Caddy publica porta, e só a 443. Com o modo Full (strict) da Cloudflare, a porta 80 não é necessária.

**Todo contêiner roda com:**
- usuário não-root;
- `read_only: true` e `tmpfs` em `/tmp`;
- `cap_drop: [ALL]`;
- `no-new-privileges`;
- limite de memória;
- log com `max-size: 10m` e `max-file: 3`;
- healthcheck.

Nenhum contêiner monta `/var/run/docker.sock`.

### Caddy

- **Certificado de origem da Cloudflare** (pode ser curinga, `*.gilvanborges.com.br`), com o modo Full (strict).
- **Authenticated Origin Pulls:** o Caddy só aceita conexões com o certificado de cliente da Cloudflare.
- **IP do cliente:** `trusted_proxies` só com as faixas da Cloudflare, e IP do cliente vindo de `CF-Connecting-IP`.
- **Hosts:**
  - `admin.` → `admin-api`;
  - `conteudo.` → só `GET` nos arquivos do snapshot, servidos do volume em modo só leitura;
  - `api.` → `ai-api` (a partir da Fase 3).

### DNS

O DNS fica na Cloudflare (nameservers `dante` e `oaklyn.ns.cloudflare.com`). Em 2 de outubro de 2026 só existia o registro principal: nenhum subdomínio, nenhum registro de e-mail.

| Nome | Tipo | Valor | Proxy | Para quê |
|---|---|---|---|---|
| `@` | já existe | portfólio | laranja | O site, fora da VPS |
| `www` | CNAME | `gilvanborges.com.br` | laranja | Uma Redirect Rule manda para o domínio principal com 301 |
| `admin` | A | IP da VPS | laranja | CRUD, atrás do Access |
| `conteudo` | A | IP da VPS | laranja | Snapshot estático |
| `api` | A | IP da VPS | laranja | IA, a partir da Fase 3 |
| `@` | MX | `0 .` (null MX) | não se aplica | O domínio não recebe e-mail |
| `@` | TXT | `v=spf1 -all` | não se aplica | Ninguém envia e-mail em nome do domínio |
| `_dmarc` | TXT | `v=DMARC1; p=reject; adkim=s; aspf=s` | não se aplica | Servidores de e-mail rejeitam mensagens falsas em nome do domínio |

Regras:
- **Todo host da VPS usa o proxy laranja.** No cinza, o tráfego passa por fora do WAF, do Access e do Turnstile, e o firewall da VPS o recusa.
- **Nada de registro curinga (`*`).** Só existe o subdomínio que tem dono.
- **Cada subdomínio da VPS precisa existir em três lugares:** no DNS, no Caddyfile e, se for privado, como aplicação no Access. Host que não está no Caddyfile não é atendido.
- **Um nível só** (`x.gilvanborges.com.br`). O certificado universal da Cloudflare e o de origem curinga não cobrem `a.b.gilvanborges.com.br`.
- **Registro sem uso é apagado.** Um registro que aponta para um serviço desativado pode ser tomado por outra pessoa (*subdomain takeover*).
- **Esconder o IP da VPS não é defesa:** o provedor costuma publicar um hostname para o servidor. O que protege é o firewall aceitar só a Cloudflare, mais o Authenticated Origin Pulls.

### Cloudflare

- **Proxy ligado** em todos os hosts.
- **Access no `admin.`:**
  - só o e-mail do Gilvan, com 2FA;
  - sessão de 8 horas;
  - cookie com `SameSite=Strict`.
- **Turnstile** no chat.
- **Uma regra de rate limit** em `/v1/chat`.
- **Bot Fight Mode desligado,** porque pode bloquear clientes MCP legítimos.

### VPS

- **Plano KVM 2** (2 vCPU, 8 GB). Com os dois lados no ar, o pico estimado passa de 4 GB, então o KVM 1 não basta.
- **O build nunca acontece na VPS.** As imagens são geradas no GitHub Actions.
- **Swap de 2 GB** como rede de segurança. Os números de memória são estimativas, medidos com `docker stats` na Fase 1 e atualizados aqui.
- **Ubuntu 26.04 LTS** (o template da Hostinger já vem com Docker 29).
- **SSH:** só com chave, sem login de root, acessível só pela Tailscale.
- **Firewall em duas camadas:**
  - **Cadeia `DOCKER-USER` do iptables, na VM** (`javai-cf-firewall`, unidade systemd): só a TCP 443 vinda das faixas IPv4 da Cloudflare chega ao Caddy; o resto é descartado. Esta é a camada que filtra a porta do Caddy, porque **o `ufw` não filtra portas publicadas pelo Docker** (o Docker escreve as próprias regras de iptables antes das do `ufw`). O IPv6 não chega aos contêineres: o `ufw` descarta a entrada.
  - **Firewall do painel da Hostinger** (fora da VM), opcional como segunda camada: a mesma regra, TCP 443 só das faixas da Cloudflare.
  - **`ufw` na VM:** nega toda entrada, exceto a 22 na interface `tailscale0`. Ele protege os serviços do próprio sistema, não os contêineres.
  - Se uma das camadas falhar, o Authenticated Origin Pulls ainda recusa qualquer TLS que não venha da Cloudflare.
- **Atualizações e proteção:** `unattended-upgrades`. Como o SSH só existe na Tailscale, o CrowdSec fica opcional.

## Deploy

1. **GitHub Actions:**
   - build e testes (Testcontainers);
   - ArchUnit e Maven Enforcer;
   - Semgrep;
   - Trivy nas imagens.
2. **Publicação:** as imagens vão para o GHCR.
3. **Atualização da VPS:** o deploy entra pela Tailscale com um usuário que só pode executar um comando, atualizar o compose.
   - **Aparelho do CI:** temporário, com a tag `tag:ci-javai`, autenticado por identidade federada (OIDC), então não há segredo da Tailscale no GitHub.
   - **Política da tailnet:** a `tag:ci-javai` só chega na porta 22 da `tag:javai`. A tailnet é compartilhada com outro projeto, que já usa a `tag:ci`; as duas nunca se misturam.
4. **Migrações:** cada lado tem o seu contêiner de migração, que roda antes do app.

As Actions ficam fixadas por SHA, com `permissions` mínimas.

**Backups:**
- snapshot semanal da Hostinger;
- `pg_dump` diário dos dois bancos, criptografado e fora da VPS;
- restauração testada todo mês.

## Observabilidade

- **IA:** `ops.usage_daily` no `postgres-ai`, com o resumo público em `GET /v1/stats`.
- **CRUD:** `ops.audit_log` e `ops.publish_job` no `postgres-admin`, visíveis só no admin.
- **Logs:** em JSON, sem pergunta, resposta nem IP.
- **Actuator:** só na rede interna.
- **Alertas por e-mail:**
  - disjuntor aberto;
  - falha na publicação ou na indexação;
  - gitleaks encontrou algo.

## Decisões tomadas

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| CRUD e IA isolados no mesmo repositório (2026-10-02) | Dois apps dividindo `core` e banco | A IA não alcança o banco do CRUD nem pela rede. O isolamento é físico, não uma regra de permissão |
| Snapshot como arquivo estático servido pelo Caddy | Rota pública no admin-api | O CRUD fica sem nenhuma rota pública |
| A IA confere o snapshot por GET condicional | Webhook do CRUD para a IA | O CRUD continua sem saber que a IA existe |
| Indexador no lado da IA | Indexador no admin-api | Embeddings e GitHub são trabalho de IA |
| Busca em memória, escrita no projeto | pgvector | O corpus é pequeno, o algoritmo fica à mostra em Java e há um componente a menos no caminho da pergunta |
| Embeddings locais (ONNX) | API de embeddings | Custo zero e nenhum texto sai do servidor |
| Sem Redis | Redis para contadores | O `ai-api` é uma instância só |
| Cliente OpenRouter próprio | ChatClient do Spring AI | Campos do OpenRouter e controle do streaming |
| O modelo nunca escreve URL | Links na resposta | Elimina a exfiltração por link |
| Identidade do admin pelo Cloudflare Access | Login próprio | Nenhuma senha no app; o app ainda valida o JWT |
| JDBC com SQL explícito (`JdbcClient`) | JPA | SQL fácil de auditar e menos memória |
| Clone raso com JGit | API do GitHub arquivo por arquivo | Sem limite de requisições e sem token |
| Admin pelo Swagger UI | Tela própria | Nenhum front para escrever agora |
| Domínios `api.` e `admin.` (2026-10-02), mais `conteudo.` para o snapshot | Tudo num host só | Cada host tem um dono claro |

## Decisões em aberto

1. **Jev em produção:** qual a API HTTP, a cota, os termos de uso e a latência real?
2. **Modelo de embeddings:** a decisão sai da avaliação da busca, na Fase 2.
3. **Tetos de gasto:** confirmar US$ 1 por dia e US$ 10 por mês.
4. **Hospedagem do portfólio:** Cloudflare Pages ou Workers? Isso define o deploy hook.
5. **`ai-indexer`:** worker separado (proposta, pelo privilégio mínimo) ou job dentro do `ai-api`?
