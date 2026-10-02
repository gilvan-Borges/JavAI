# 01 · Arquitetura

**Status:** rascunho · **Atualizada em:** 2026-10-02

## Visão geral

```
Navegador ── gilvanborges.com.br (site estático na Cloudflare)
   │  o Turnstile gera um token
   │  POST /v1/chat (fetch + streaming)
   ▼
Cloudflare ── DNS, TLS, WAF, rate limit, Access no admin
   │  só a Cloudflare alcança a origem (firewall + Authenticated Origin Pulls)
   ▼
VPS Hostinger ── Caddy
   ├── api.gilvanborges.com.br   ─► ai-api    (público)
   └── admin.gilvanborges.com.br ─► admin-api (só o Gilvan)

ai-api    ──► Jev (guarda + roteador)
          ──► OpenRouter (só modelos da lista fixa)
          ──► PostgreSQL com o papel javai_ai (lê o índice ativo, grava contadores)

admin-api ──► PostgreSQL com o papel javai_admin (CRUD, índice, auditoria)
          ──► GitHub (clone raso dos repositórios ligados)
          ──► deploy hook do portfólio

Cliente MCP (Claude, ChatGPT...) ── POST /mcp ─► ai-api
```

## Módulos

O JavAI é um projeto Maven multimódulo em Java 25, organizado em arquitetura hexagonal.

| Módulo | O que contém | Depende de |
|---|---|---|
| `core` | Domínio e portas: busca híbrida, guardas, roteador, montagem do prompt, assinatura do histórico. Java puro, sem Spring | Só o JDK |
| `adapters` | Implementações das portas: OpenRouter, Jev, embeddings ONNX, PostgreSQL, GitHub | `core` |
| `ai-api` | App Spring Boot público: `/v1/*` e `/mcp` | `core`, `adapters` |
| `admin-api` | App Spring Boot privado: CRUD, indexador, Swagger UI | `core`, `adapters` |
| `migrations` | Scripts Flyway, que rodam uma vez a cada deploy | Nenhum |
| `evals` | Bateria de avaliação em JUnit 5, rodando contra uma instância real via HTTP | Nenhum módulo de produção |

Um teste ArchUnit quebra o build se o `core` importar Spring ou qualquer coisa de `adapters`.

**Versões** (estáveis em 2 de outubro de 2026): Java 25 (LTS), Spring Boot 4.1, SDK oficial de MCP para Java 2.0, PostgreSQL 18.

**Cliente do OpenRouter próprio,** feito com `java.net.http.HttpClient`, por dois motivos:
- O JavAI precisa de campos que só existem no OpenRouter (`models`, `provider`, `max_price`).
- A guarda de saída precisa controlar o streaming caractere a caractere.

O Spring AI fica como alternativa caso o cliente próprio não compense.

## Fluxo de uma pergunta no chat

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

Se o visitante fecha a conexão em qualquer etapa, a chamada ao OpenRouter é cancelada.

O contexto da requisição (id, hash do IP, idioma, orçamento restante) é carregado com Scoped Values, e não com ThreadLocal.

## Roteador

O Jev classifica cada pergunta numa destas categorias:

| Categoria | Ação | Nível do modelo |
|---|---|---|
| `perfil` | responde | `rapido` |
| `codigo` | responde | `forte` |
| `vaga` | responde | `forte` |
| `fora_do_escopo` (p ≥ 0,6) | recusa sem chamar modelo | nenhum |
| `ataque` (p ≥ 0,5) | recusa sem chamar modelo | nenhum |

- **Os limiares acima são só o ponto de partida.** A bateria de [05](05-avaliacao.md) calibra os valores seguindo uma regra: nenhuma pergunta legítima pode ser barrada.
- **Os modelos de cada nível vêm da configuração do servidor** (`javai.models.rapido`, `javai.models.forte`), cada um com fallback.
- **A requisição não tem campo de modelo,** e qualquer campo desconhecido gera 400.
- **Um modelo só entra na configuração depois de passar na bateria.**

Parâmetros enviados em toda chamada ao OpenRouter:

```json
{
  "models": ["<principal>", "<fallback>"],
  "stream": true,
  "max_tokens": 300,
  "provider": {
    "data_collection": "deny",
    "zdr": true,
    "max_price": { "prompt": 0, "completion": 0 }
  }
}
```

- **Os valores de `max_price` são definidos junto com os modelos, na Fase 4.**
- **Se `zdr: true` restringir demais os provedores,** fica só `data_collection: "deny"`.
- **O raciocínio do modelo fica desligado** quando o modelo permite.

**Se o Jev falhar** (erro, cota esgotada ou mais de 800 ms), a pergunta segue com o nível `rapido`. As outras camadas continuam valendo, e a falha entra num contador.

## Busca

- **Índice em memória.** O índice ativo é carregado na memória do ai-api quando ele sobe e a cada `NOTIFY javai_index`. O novo índice é montado ao lado do antigo e trocado de uma vez (`AtomicReference`). Perguntas que já estavam em andamento terminam no índice antigo.
- **BM25 implementado no `core`.** O texto é normalizado (minúsculas, NFKC, acentos removidos só para comparar) e as stopwords em português e inglês são descartadas.
- **Cosseno sobre embeddings normalizados,** que na prática é um produto escalar. A interface `VectorOps` tem duas implementações:
  - escalar;
  - Vector API, que ainda é incubadora (`--add-modules jdk.incubator.vector`).
  O benchmark JMH entre as duas fica no repositório.
- **RRF (k = 60)** junta as duas listas, e os 5 primeiros trechos vão para o modelo.
- **Cada trecho tem um título em terceira pessoa** ("Experiência de Gilvan Borges na Vibetex"). O conteúdo é escrito em primeira pessoa e as perguntas chegam em terceira; sem o título, a busca não aproxima os dois.
- **O rerank com o Jev é opcional.** Entra só se a avaliação da busca mostrar ganho ([05](05-avaliacao.md#avaliação-da-busca)).

## Embeddings

- **Modelo candidato:** `multilingual-e5-small`, com 384 dimensões e suporte a português e inglês. Roda com o ONNX Runtime para Java e o tokenizer da DJL, e exige os prefixos `query: ` e `passage: `.
- **Verificação do arquivo:** o modelo é baixado no build da imagem e conferido por SHA-256. O app não sobe se o hash não bater.
- **Quem gera o quê:** o indexador gera os embeddings dos trechos; o ai-api gera só o da pergunta.
- **Memória:** a versão quantizada em int8 é a preferida, porque ocupa cerca de um quarto da fp32. A troca só acontece se a avaliação da busca não mostrar perda. O admin-api abre a sessão ONNX no começo da indexação e fecha no fim, então fica leve no resto do tempo. A memória do ONNX Runtime é nativa e não entra no heap da JVM, por isso o limite de memória do contêiner precisa cobrir o heap e mais o modelo.
- **Plano B:** se a avaliação da busca mostrar uma perda grande em relação a uma API de embeddings, basta trocar a implementação da porta `EmbeddingModel`, sem mexer no `core`.

## Guardas

O detalhe de cada ameaça está em [04](04-ameacas.md). Em resumo:

- **Entrada:**
  - limites de tamanho;
  - campos desconhecidos rejeitados;
  - tags que imitam os delimitadores do prompt são removidas da pergunta.
- **Jev:** classifica a pergunta como `ataque` ou `fora_do_escopo`.
- **Prompt:**
  - nada secreto;
  - um canário aleatório por versão do prompt;
  - dados dentro de `<fonte id="n">`;
  - o modelo cita as fontes por número (`[n]`) e **nunca escreve URL**, porque quem monta os links é o servidor.
- **Saída:**
  - o servidor segura os últimos N caracteres antes de repassar, sendo N o tamanho do maior marcador;
  - a guarda dispara se aparecer canário, URL (`http`, `www.`), HTML ou markdown de imagem;
  - quando dispara, cancela a chamada e envia `retract`.
- **Histórico:**
  - cada turno é assinado com HMAC-SHA256;
  - a chave é derivada com HKDF (API de KDF do JDK) a partir de um segredo mestre;
  - cada turno vale 2 horas;
  - no máximo 4 turnos por conversa.

## Indexador (admin-api)

**Gatilhos:** `POST /admin/v1/publish` e um agendamento diário às 03:00 (horário de São Paulo).

1. **Cria uma `ai.index_version`** com status `building`.
2. **Lê o conteúdo do CRUD:** só as linhas `published` e `public`, e só os campos de uma lista explícita. `private_notes` e campos privados nunca entram.
3. **Lê os repositórios:** cada linha de `content.github_repo` com `enabled`. O banco não deixa ligar fork nem repositório privado.
   - **Metadados pela API do GitHub:** descrição, tópicos, linguagens, `fork`, `private`, `size`. Se o repositório virou privado, ele é pulado.
   - **Limite de tamanho:** se o `size` informado pelo GitHub passar de `javai.indexer.max-repo-size` (50 MB por padrão), o repositório não é clonado e o relatório do job registra o motivo. O `size` mede o repositório inteiro, com histórico, então serve como teto e não como medida exata do que o clone raso baixa.
   - **Clone:** `git clone --depth 1` do branch padrão, por HTTPS, com JGit e sem token, numa pasta temporária apagada no fim.
   - **Filtro de arquivos** (lista abaixo).
   - **gitleaks na pasta:** arquivo com achado é pulado, e o relatório guarda só o caminho e a regra, nunca o valor.
4. **Calcula as evidências de habilidades,** sem LLM (tabela abaixo).
5. **Monta os trechos:**
   - README dividido por seção;
   - código por arquivo, com cabeçalho de repositório, caminho, pacote e classe;
   - arquivo grande é dividido;
   - cada trecho recebe um título em terceira pessoa.
6. **Gera os embeddings.**
7. **Grava o snapshot do conteúdo publicado** (`ai.content_snapshot`), que o portfólio usa no build.
8. **Ativa a versão** numa transação e faz `NOTIFY javai_index`.
9. **Chama o deploy hook do portfólio.**
10. **Guarda as 3 últimas versões** para rollback.

**Arquivos que entram:**
- `.java`, `.kt`, `.ts`, `.md`;
- `pom.xml`, `build.gradle(.kts)`, `package.json`, `Dockerfile`;
- `docker-compose*.yml`, `compose*.yaml`;
- `.github/workflows/*.yml`;
- `db/migration/*.sql`.

**Arquivos que nunca entram:**
- pastas `node_modules`, `target`, `build`, `dist`, `.angular`, `.mvn`, `vendor`;
- lockfiles e binários;
- `application*.properties` e `application*.yml`;
- `.env*`;
- arquivos com mais de 100 KB.

**Evidências de habilidades** (calculadas sem LLM):

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
- **Habilidade sem evidência:** se ela foi declarada no CRUD, aparece como "declarada, sem evidência pública". Não é escondida nem inventada.

## Integração com o portfólio

- **Build:** o portfólio busca `GET /v1/content/snapshot?lang=pt|en`. Se a busca falhar, o build falha e o deploy anterior continua no ar.
- **Chat:** o portfólio usa o widget do Turnstile e chama `/v1/chat`. A resposta é exibida só com `textContent`, nunca com `innerHTML`.
- **Cabeçalhos:** o portfólio precisa de cabeçalhos de segurança, que hoje não tem nenhum. O CSP deve incluir `connect-src https://api.gilvanborges.com.br`.

## Custos e limites

Valores iniciais propostos:

| Limite | Valor |
|---|---|
| Perguntas por IP | 3 por minuto, 10 por dia |
| Chamadas MCP por IP | 60 por minuto |
| Teto diário de gasto (disjuntor) | US$ 1,00 |
| Limite de crédito na chave do OpenRouter | US$ 10 por mês |
| `max_tokens` da resposta | 300 |
| Tamanho da pergunta | até 500 caracteres |

- **Onde fica o rate limit:** em memória, com Bucket4j (o ai-api roda numa instância só), usando o hash do IP como chave.
- **Como o IP é guardado:** o hash é `HMAC(chave do dia, IP)`, com a chave do dia derivada por HKDF. Não dá para reverter o hash nem ligar o mesmo IP entre dias diferentes.
- **De onde vem o custo:** o OpenRouter informa o custo de cada resposta, e o total vai para `ops.usage_daily`.
- **O disjuntor** abre quando o gasto do dia atinge o teto e fecha à meia-noite (horário de São Paulo).

## Infraestrutura

### Serviços no docker-compose

| Serviço | Redes | Porta publicada | Memória |
|---|---|---|---|
| `caddy` | `edge` | 80, 443 | 128 MB |
| `ai-api` | `edge`, `data` | nenhuma | 1 GB (JVM, modelo ONNX e índice em memória) |
| `admin-api` | `edge`, `data` | nenhuma | 1 GB (o modelo só é carregado durante a indexação) |
| `postgres` | `data` (`internal: true`) | nenhuma | 512 MB |
| `migrate` | `data` | nenhuma | roda uma vez e sai |

**Não há Redis.** O ai-api é uma instância só, os contadores de rate limit ficam em memória e o gasto diário fica no PostgreSQL.

**O Docker ignora o `ufw`:** uma porta publicada fica aberta para a internet mesmo com o firewall bloqueando. Por isso só o Caddy publica portas.

**Todo contêiner roda com:**
- usuário não-root;
- `read_only: true` e `tmpfs` em `/tmp`;
- `cap_drop: [ALL]`;
- `no-new-privileges`;
- limite de memória;
- log `json-file` com `max-size: 10m` e `max-file: 3`;
- healthcheck.

Nenhum contêiner monta `/var/run/docker.sock`.

### Caddy

- **Certificado de origem da Cloudflare,** com SSL no modo Full (strict).
- **Authenticated Origin Pulls:** o Caddy só aceita conexões que apresentam o certificado de cliente da Cloudflare.
- **IP do cliente:** `trusted_proxies` contém só as faixas de IP da Cloudflare, e o IP do cliente vem de `CF-Connecting-IP`.
- **Limite de tamanho do corpo** das requisições.
- **Actuator fora do roteamento:** ele não é exposto pelo Caddy.

### Cloudflare

- **Proxy ligado** nos dois hosts.
- **Access no admin:**
  - a política aceita só o e-mail do Gilvan, com 2FA;
  - a sessão dura 8 horas;
  - o cookie usa `SameSite=Strict`.
- **Turnstile** no chat.
- **Uma regra de rate limit** em `/v1/chat`.
- **Bot Fight Mode desligado,** porque pode bloquear clientes MCP legítimos.

### VPS

- **Plano KVM 2:** 2 vCPU e 8 GB de RAM.
  - O mínimo técnico é 4 GB (KVM 1), mas o pico estimado fica perto de 3 GB e a folga seria pequena.
  - O build nunca acontece na VPS: Maven e imagens são gerados no GitHub Actions.
  - Um swap de 2 GB serve de rede de segurança contra o OOM killer.
  - Os números são estimativas. Na Fase 1 eles são medidos com `docker stats` e atualizados aqui.
- Ubuntu 24.04 LTS.
- **SSH:**
  - só com chave;
  - login de root bloqueado;
  - acessível só pela Tailscale.
- **Firewall:**
  - `ufw` liberando 80 e 443 só para as faixas de IP da Cloudflare;
  - o firewall do painel da Hostinger como segunda camada.
- **Atualizações e proteção:** `unattended-upgrades` e CrowdSec (ou fail2ban).

## Deploy

1. **GitHub Actions:**
   - build;
   - testes unitários e de integração (Testcontainers);
   - ArchUnit;
   - Semgrep;
   - Trivy nas imagens.
2. **Publicação:** as imagens vão para o GHCR.
3. **Atualização da VPS:** o deploy entra pela Tailscale com um usuário que só pode executar um comando (forced command): atualizar o compose.
4. **Migrações:** o contêiner `migrate` aplica o Flyway com o papel `javai_owner` antes de os apps subirem. Os apps não conhecem a senha do dono.

As Actions ficam fixadas por SHA, e cada workflow declara `permissions` mínimas.

**Backups:**
- snapshot semanal da Hostinger;
- `pg_dump` diário, criptografado e guardado fora da VPS;
- restauração testada todo mês.

## Observabilidade

- **Contadores:** `ops.usage_daily` guarda requisições, recusas, tokens e custo por dia e por modelo. O resumo é público em `GET /v1/stats`.
- **Logs:** em JSON, sem a pergunta, sem a resposta e sem o IP (só o hash).
- **Actuator:** só na rede interna.
- **Alertas por e-mail:**
  - disjuntor aberto;
  - falha no indexador;
  - gitleaks encontrou algo;
  - modelo de fallback usado mais de N vezes no dia.

## Decisões tomadas

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| Busca em memória, escrita no projeto | pgvector | O corpus é pequeno (centenas a poucos milhares de trechos), o algoritmo fica à mostra em Java e há um componente a menos no caminho da pergunta |
| Embeddings locais (ONNX) | API de embeddings | Custo zero e nenhum texto sai do servidor. A qualidade é validada na avaliação da busca |
| Sem Redis | Redis para os contadores | Há uma instância só: Bucket4j em memória e gasto diário no PostgreSQL |
| Cliente OpenRouter próprio | ChatClient do Spring AI | Campos específicos do OpenRouter e controle total do streaming para a guarda de saída |
| O modelo nunca escreve URL | Permitir links na resposta | Elimina por construção a exfiltração de dados por link |
| Identidade do admin pelo Cloudflare Access | Login próprio no app | Nenhuma senha no app e 2FA do provedor. O app ainda valida o JWT do Access |
| Clone raso com JGit | API do GitHub arquivo por arquivo | Sem limite de requisições e sem token |
| Dois apps separados | Um app com perfis | O app público não carrega código de escrita, e cada um usa um papel diferente no banco |
| Admin pelo Swagger UI | Tela própria | Nenhum front para escrever agora. Dá para fazer uma tela depois, sobre a mesma API |

## Decisões em aberto

1. ~~Domínios~~: decidido em 2026-10-02, `api.gilvanborges.com.br` e `admin.gilvanborges.com.br`.
2. **Jev em produção:** qual a API HTTP, a cota, os termos de uso e a latência real? Hoje ele é usado via MCP.
3. **Modelo de embeddings:** `multilingual-e5-small` ou outro? A decisão sai da avaliação da busca, na Fase 2.
4. **Tetos de gasto:** confirmar US$ 1 por dia e US$ 10 por mês.
5. **Hospedagem do portfólio:** Cloudflare Pages ou Workers? Isso define o deploy hook.
6. **Alertas:** o canal será e-mail ou push?
