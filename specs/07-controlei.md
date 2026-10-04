# 07 · Controlei (finanças familiares com IA nos comprovantes)

**Status:** aprovada · **Atualizada em:** 2026-10-04

Repositório próprio: <https://github.com/gilvan-Borges/controlei>. Esta spec é a régua dele, no mesmo formato das specs do JavAI, e liga cada decisão às duas ementas ([06](06-base-academica.md)).

## Em uma frase

O Controlei é um sistema de finanças para uma família, em Java 25 e Angular 21, com visão individual e consolidada, eventos em Kafka com Transactional Outbox e leitura de comprovantes por IA em que o modelo só sugere e o código valida.

## O problema

Uma família divide contas, cartão, parcelas e metas, mas cada pessoa lança seus gastos num lugar. Ninguém vê o conjunto, e o lançamento manual é a parte que as pessoas abandonam primeiro.

## O que o sistema faz

| Área | O que existe | Estado |
|---|---|---|
| Família e papéis | Uma família por usuário; `RESPONSIBLE` edita tudo, `MEMBER` edita só o que é seu | Implementado, com teste de acesso entre famílias |
| Contas, categorias, transações | CRUD com soft delete e auditoria (`created_by`, `updated_by`, `deleted_at`) | Implementado |
| Dívidas e parcelas, cartão e faturas | Parcelas geradas ao criar a dívida; cartão, compras e fatura | Implementado |
| Recorrências, orçamentos, metas | Transações recorrentes, orçamento mensal com alerta, metas com contribuições | Implementado |
| Rateio de despesas | Divisão entre membros e liquidação ("quem deve para quem"), regra no domínio | Implementado e testado por propriedade |
| Investimentos, dashboard, relatórios, notificações | Visão individual e familiar, extrato em CSV, alertas | Implementado |
| Planos (assinatura) | Plano e cota de membros, com eventos de ativação e expiração | Implementado (sem cobrança real) |
| **Leitura de comprovantes** | Imagem ou texto vira valor, data, estabelecimento e categoria sugerida | **Implementado com IA (ver abaixo)** |
| Open Finance | Sincronização bancária | **Simulado:** cria uma transação de exemplo. O webhook exige assinatura HMAC, mas não há integração com banco |
| Keycloak | Constava no Compose, sem uso no código | **Removido do Compose.** Autenticação é JWT próprio (access de 15 min + refresh rotativo), BCrypt custo 12 e bloqueio por conta |

O que está simulado aparece escrito assim no README e no portfólio. Nada simulado é apresentado como real.

## Arquitetura

```
 Angular 21 ──► Nginx (única porta publicada) ──► Spring Boot 4.1 (Java 25, virtual threads)
                                                     ├─ domain          regras puras: SplitCalculator, DebtSimplifier
                                                     ├─ application     casos de uso, controllers, portas
                                                     └─ infrastructure  JPA, JWT, outbox, Kafka, Redis, IA
                                                           │
   PostgreSQL 16 (Flyway) ◄── dado + outbox_events (MESMA transação)
                                                           │
                        OutboxRelay ──► Kafka (KRaft) ──► BudgetMonitoringConsumer ──► Redis (chave de idempotência)
                                                           │ falhou 3x
                                                           └──► <tópico>.DLT
                                       OpenRouter (só comprovantes, só se ligado)
```

Regras que não mudam, e que agora são **testes** (`ArchitectureTest`, ArchUnit):

1. **O domínio não conhece as outras camadas nem Spring/JPA.** Só a validação Jakarta nos DTOs é exceção conhecida.
2. **A aplicação não depende da infraestrutura.** Fala com ela por portas (`DomainEventPublisher`, repositórios, `ReceiptAiClient`).
3. **Controllers só falam com services**; entidades JPA não saem da infraestrutura; sem injeção por campo; portas são interfaces.
4. **Tudo pertence a uma família.** Todo acesso passa por `AuthorizationService`; acessar outra família é teste de integração (e responde 404, sem confirmar que o id existe).
5. **Dinheiro é `BigDecimal`** (`DECIMAL(19,4)`). Nunca `double`. A soma das partes de uma divisão é sempre o total, em centavos (teste por propriedade com milhares de casos).
6. **Nada é apagado de verdade.** Soft delete e trilha de auditoria.
7. **Banco só muda por migration Flyway** (`V1` a `V23`), com `validate` em produção.

### Transactional Outbox

Publicar no Kafka dentro de uma transação é um *dual write*: o evento pode sair e a transação sofrer rollback, ou o dado ser gravado e o evento perdido com o broker fora. O código antigo ainda engolia a exceção em silêncio.

Agora o service chama a porta `DomainEventPublisher`; a implementação grava o evento em `outbox_events` **na mesma transação** do dado (`Propagation.MANDATORY`: fora de transação falha de propósito). O `OutboxRelay` entrega ao Kafka depois do commit:

| Garantia | Como |
|---|---|
| Dado e evento confirmam juntos ou nenhum dos dois | Mesma transação; teste de rollback sem evento |
| Pelo menos uma vez | Marca como publicado só após o envio; o consumidor é idempotente |
| Ordem | Coluna `seq` (identity); a primeira falha interrompe o lote. Limite documentado: duas transações concorrentes da mesma família podem confirmar fora de ordem |
| Várias instâncias | `FOR UPDATE SKIP LOCKED` |
| Mensagem venenosa | Após 10 falhas vira `dead_at` e sai da fila, para não travar o resto |
| Segurança | O relay só reconstrói classes do pacote `shared.events` |

No consumidor, a chave de idempotência é **liberada se o processamento falha** (antes, uma falha transitória descartava o evento para sempre). Falha repetida: 3 retentativas e envio para `<tópico>.DLT`. A regra do alerta saiu do consumidor para `BudgetAlertService` (testável sem broker), e o total gasto agora é uma **soma no banco**, não o mês inteiro carregado em memória a cada evento.

## Implantação (mesma VPS do JavAI, com a Tailscale como padrão)

O Controlei roda na VPS do JavAI como um **segundo projeto Compose** (`/opt/controlei`), sem misturar o que não precisa ser compartilhado.

| Decisão | Como | Por quê |
|---|---|---|
| Nenhuma porta publicada pelo Controlei | O Caddy do JavAI entra por uma rede externa `controlei-edge` (subnet fixa) e fala com o gateway | O Docker ignora o `ufw`: toda porta publicada fica aberta. Um único ponto de entrada, que já exige a Cloudflare e o Authenticated Origin Pulls |
| Tailscale como caminho padrão do que é interno | SSH, deploy do CI (OIDC, `tag:ci-javai`, usuário `deploy-controlei` com comando forçado que só aceita um SHA) e Kafka UI preso ao IP da Tailscale | Nada interno precisa existir na internet pública; não há segredo da Tailscale no GitHub |
| Usuário, chave e script próprios | `deploy-controlei` e `controlei-deploy-root`, sem tocar o `deploy` do JavAI | Privilégio mínimo: um deploy não alcança o outro projeto |
| Tetos de memória por serviço (~1,7 GB) | `mem_limit` em todos; heap do Kafka em 384 MB | A VPS tem 7,9 GB e o JavAI já usa ~1,8 GB; o Controlei não pode derrubar vizinhos |
| Dados em rede `internal` | Postgres, Redis (com senha) e Kafka sem rota para a internet | Só o `back` os alcança; a rede `app` tem saída apenas para o provedor de IA, quando ligado |
| IP real atrás de dois proxies | O Caddy manda `X-Real-IP`; o gateway só o aceita vindo da subnet da rede de borda | Sem isso, todos os visitantes dividiriam um limite de requisições |
| Instância **pública** (decisão do dono, 2026-10-04) | Sem Cloudflare Access e com cadastro aberto na VPS; o padrão do `.env.vps.example` continua fechado. Limites por IP no gateway, bloqueio de login por e-mail, IA desligada | O Controlei é uma vitrine que precisa ser testável por quem avalia. Ficou para depois um CAPTCHA no cadastro e o backup do Postgres. Para fechar de novo: `REGISTRATION_ENABLED=false` ou recriar a política do Access |

O passo a passo, os segredos e o que ainda é manual (DNS, Access, credencial OIDC da Tailscale para este repositório, variáveis do GitHub) estão em `deploy/README.md` do repositório do Controlei e em `infra/README.md`, seção 6.

**Estado (2026-10-04):** em produção na VPS, atrás do Cloudflare Access, em `controlei.gilvanborges.com.br`. Foi executado e verificado: construção e varredura das imagens no CI (o Trivy barrou três vezes, por dependências do back, pela base antiga do nginx e por um pacote; todas corrigidas), as migrations V1 a V23 em Postgres real, o stack com os seis serviços saudáveis (~800 MB), a rota no Caddy sem afetar os hosts do JavAI e um **deploy automático completo pelo CI** (OIDC, Tailscale, SSH com comando forçado, script com rollback). O primeiro start achou um erro de nginx que o `nginx -t` teria pego; agora ele roda no CI. **Ainda pendentes:** backup do Postgres, o relay do outbox e o consumidor contra um Kafka com carga real (só vimos o broker subir e o back ficar saudável), e abrir ao público (hoje só o dono entra).

## IA: leitura de comprovantes

A mesma ideia do JavAI: o modelo escreve, o código decide.

```
imagem ou texto ──► tipo real pelos bytes ──► [IA ligada, cota e disjuntor ok?] ──não──► regras (regex) ──► sugestão
                                                          │sim
                                                          ▼
                                         OpenRouter (data_collection=deny), FORA da transação
                                                          │ JSON bruto
                                                          ▼
                                         validação em Java (LlmReceiptExtractor)
                                                          │
                                                          ▼
                              status COMPLETED ou NEEDS_REVIEW ──► o usuário confirma ──► transação
```

O que o código faz com a resposta do modelo, tratada como entrada hostil:

| Verificação | Por quê |
|---|---|
| O valor precisa aparecer no texto lido (ou na transcrição) | Barra valor inventado, o equivalente da guarda de saída do JavAI |
| Valor positivo e até R$ 1.000.000; data entre 5 anos atrás e amanhã | Barra negativo, zero, absurdo e data impossível |
| Categoria só entre as da família, comparada pelo nome | O modelo nunca escolhe identificador; "apagar todas as contas" não vira categoria |
| Confiança do modelo limitada a 0,90, e a 0,30 sem valor | A confiança que o modelo declara não é prova |
| Texto cortado e sem caracteres de controle | Limita o que chega ao banco e à tela |
| Comprovante entre `<comprovante>` e instrução de que é dado | Defesa de prompt injection em camadas, não só no prompt |
| Falha, timeout ou JSON inválido caem nas regras | A IA é opcional; o sistema funciona sem ela |

Segurança, custo e desempenho da camada de IA:

| Controle | Detalhe |
|---|---|
| Tipo do arquivo pelos **bytes** (JPEG, PNG, PDF) | O `Content-Type` vem do cliente e não prova nada; antes, qualquer arquivo ia ao provedor rotulado como imagem |
| Nome do arquivo sanitizado | Só o último segmento, caracteres seguros, 120 caracteres |
| **Cota diária por família** (`CONTROLEI_AI_DAILY_LIMIT`, 30) | Cada leitura custa dinheiro; passou da cota, cai no plano B |
| **Disjuntor** (3 falhas seguidas, 60 s de pausa) | Provedor fora do ar não prende cada upload por 30 s |
| Chamada ao modelo **fora da transação** | Antes, cada leitura segurava uma conexão do banco por até ~35 s; ~20 uploads esgotariam o pool e derrubariam a API |
| Teto de 4 MB de imagem ao modelo; 10 MB de upload | Custo, latência e memória do base64; limite do multipart declarado (o padrão do Spring é 1 MB) |
| Limite de 10 leituras/min por IP no gateway | Camada extra, antes da cota |
| Métricas: `controlei.receipts.ai{outcome=ok\|rejected\|failed\|skipped_circuit_open}` e latência | Dá para ver a taxa de rejeição e de fallback |
| IA desligada por padrão; `data_collection=deny`; nada do conteúdo no log; arquivo não guardado | A imagem sai do servidor |

Sem IA, imagem e PDF **não são lidos**: voltam como `NEEDS_REVIEW`, sem dados. Antes, o sistema devolvia um texto fixo de supermercado com 95% de confiança. Nada cria transação sozinho (humano no circuito); a tela mostra "Revisar" quando a leitura não é confiável.

## Auditoria de 2026-10-04

Três revisões independentes (segurança, arquitetura, desempenho) leram o código. O que encontraram e o que foi feito:

### Segurança

| Achado | Gravidade | Estado |
|---|---|---|
| Postgres, Redis, Kafka, Kafka UI e Keycloak publicados no host, com senhas padrão (`controlei123`, `admin123`) | Crítica | **Corrigido:** só o gateway publica porta; senha do banco obrigatória; Keycloak e Kafka UI fora do Compose de produção (o UI vive no override de dev, em 127.0.0.1) |
| Seed com `superadmin@controlei.local / Controlei@123` ativo em produção | Crítica | **Corrigido:** seed só no perfil `local`, desligado no Compose, sem senha padrão no código |
| Credenciais do seed embutidas no front (botão "acesso rápido") | Alta | **Corrigido:** vivem em `environment.ts`, que o build de produção não inclui; verificado no bundle |
| IDOR: conectar banco apontando para a conta de outra família | Alta | **Corrigido:** valida a família no cadastro e na sincronização; teste de ataque |
| Webhook público sem verificação de assinatura | Alta | **Corrigido:** HMAC-SHA256 sobre o corpo, comparação em tempo constante; sem segredo, nada é aceito |
| Rate limit contornável com `X-Forwarded-For` forjado | Alta | **Corrigido:** o nginx sobrescreve os cabeçalhos com o IP da conexão; o filtro só confia neles em produção |
| Token de acesso de 24 h (a regra de `hours` vencia a de minutos) | Média | **Corrigido:** 15 min, renovação transparente no front (uma renovação por vez, porque o refresh token rotaciona) |
| Refresh token em texto puro no banco | Média | **Corrigido:** guarda-se o SHA-256; a migration V21 invalida os tokens antigos |
| Troca de senha não revogava sessões; revogação em lote não limpava o cache do JPA | Média | **Corrigido** (e o segundo era bug real, achado por teste) |
| `JWT_SECRET` de exemplo passava na validação | Média | **Corrigido:** em produção recusa valores de exemplo e pouca variedade de caracteres |
| Sem bloqueio por conta; senha mínima de 6; BCrypt custo 10; resposta de login mais rápida quando o e-mail não existe | Média | **Corrigido:** 5 falhas travam 15 min (vale também para e-mails inexistentes), senha de 10 a 72, custo 12, BCrypt sempre executado, e-mail normalizado |
| CSV com injeção de fórmula (`=`, `+`, `-`, `@`) e quebra de linha | Média | **Corrigido** |
| `/actuator` público; sem CSP, `Permissions-Policy` ou `server_tokens off` | Média | **Corrigido:** só `/actuator/health` é público (em produção, só ele existe); CSP sem `unsafe-inline` em scripts |
| Token no `localStorage` | Baixa | **Mitigado, não eliminado:** CSP estrita, sem `innerHTML` no front. Cookie `HttpOnly` exigiria BFF; é a evolução natural |
| Qualquer membro cria categorias, notificações e audit-log completo | Baixa | **Pendente** (decisão de produto) |
| Upgrade de plano sem cobrança | Baixa | **Pendente:** é stub declarado |
| Segredo JWT antigo no histórico do Git | — | **Pendente (ação do dono):** rotacionar |

### Arquitetura

| Achado | Estado |
|---|---|
| Evento publicado dentro da transação, exceção engolida (dual write) | **Corrigido:** Transactional Outbox |
| `application` dependia de `infrastructure` (2 services importavam o publicador do Kafka) | **Corrigido** e travado por ArchUnit |
| Regra de rateio e liquidação dentro de um service de 400 linhas | **Corrigido:** `SplitCalculator` e `DebtSimplifier` em `domain.services`, puros, testados por propriedade; achou dois bugs (membro duplicado perdia dinheiro; ordem dependia de `HashMap`) |
| Regra do alerta de orçamento dentro do consumidor Kafka | **Corrigido:** `BudgetAlertService` |
| Lombok declarado sem uso; sem Enforcer | **Corrigido** |
| **Domínio ainda anêmico** nas entidades (getters e setters; regra em services como `CreditCardService`) | **Pendente.** Foi extraído o que tinha mais valor (rateio, liquidação). Mover parcelas do cartão e orçamento para o domínio é a próxima etapa |
| DTOs com validação Jakarta dentro de `domain` | **Pendente** (documentado como exceção na regra ArchUnit) |
| Testes em H2 com schema duplicado e divergente do de produção | **Pendente:** exige Testcontainers (ver abaixo) |
| `application/usecases` e `domain/services` citados nas docs | `domain/services` agora existe; `usecases` não é usado (os services cumprem o papel) |
| Entidades de domínio com construtores de 12+ parâmetros; sem `sealed` | **Pendente** |

### Desempenho

| Achado | Estado |
|---|---|
| Chamada ao LLM (até 35 s) dentro de `@Transactional` | **Corrigido** |
| Orçamento e consumidor carregavam o mês inteiro em memória (N consultas × T transações) | **Corrigido:** soma no banco |
| CSV com 3 consultas por linha | **Corrigido:** nomes consultados uma vez por id |
| Sincronização bancária com consulta dentro do laço | **Corrigido** |
| Índices só de uma coluna em `transactions`; faltavam em audit, notificações, parcelas | **Corrigido na V23** (parciais, `deleted_at IS NULL`) — **não executada em Postgres real** |
| Listagens de audit-log e notificações sem limite | **Corrigido:** as 200 mais recentes |
| Nginx do front sem gzip nem cache; keep-alive do proxy | **Corrigido** |
| Producer Kafka sem `max.block.ms` | **Corrigido** (5 s) |
| Dashboard familiar: 5 consultas por membro | **Pendente** |
| Outras listagens sem paginação (rateio, metas, investimentos, cartão) e N+1 de nomes | **Pendente** |
| Sem cache de dashboard e categorias | **Pendente** |
| Front sem `OnPush` e sem cancelamento de requisição ao trocar filtro | **Pendente** |

## Mapa para as ementas

| Tema da ementa | No Controlei | Estado |
|---|---|---|
| E1·1 Fundamentos de Java | Java 25, records, switch com expressões, streams, `BigDecimal`, exceções tipadas | Atende |
| E1·2 Spring Boot, REST, JWT, JPA | Spring Boot 4.1, REST, Bean Validation, Spring Security, JPA + Flyway; JWT próprio com refresh rotativo e hash | Atende |
| E1·3 Front-end | Angular 21 + Bootstrap, lazy loading, interceptor com renovação de sessão | Atende (sem OnPush) |
| E1·4 Arquitetura (clean, event-driven) | Camadas verificadas por ArchUnit; Kafka + Transactional Outbox + consumidor idempotente + DLT | Atende |
| E1·5 DDD, patterns, system design | Portas e adaptadores; domain services puros; outbox; disjuntor; cota. Entidades ainda anêmicas | Parcial |
| E1·6 Concorrência | Virtual threads; `SKIP LOCKED` no relay; sem `synchronized` (sem pinning) | Atende |
| E1·7 Infra (Docker, CI/CD) | Compose com uma única porta publicada e segredos obrigatórios, validado no CI; imagem do back em usuário não root, com heap proporcional ao container. As imagens ainda não foram construídas nesta revisão. Sem deploy | Parcial |
| E1·8 Bancos | PostgreSQL, 23 migrations, soft delete, auditoria, índices parciais, outbox | Atende (migrations V21-V23 não rodaram em Postgres real) |
| E1·9 Testes | 197 no back (unitários puros, por propriedade, integração com MockMvc e H2, ArchUnit) e 44 no front (Vitest) | Parcial (sem Testcontainers, sem Kafka real, sem cobertura medida) |
| E2·2 APIs de IA, custo, consistência | OpenRouter, `temperature=0`, saída JSON validada, cota por família, disjuntor, métricas | Atende |
| E2·2 Redução de alucinação | Valor tem de constar no texto; categoria só da lista | Atende |
| E2·4 Agentes, guardrails, HITL | Sem agente; guardrails de saída e confirmação humana antes da transação | Atende |
| E2·10 Segurança e governança | IA desligada por padrão, `data_collection=deny`, sem conteúdo em log, auditoria de segurança registrada aqui | Atende |
| E2·9 Fine-tuning | **Decisão contrária:** extração com modelo geral e saída validada; ajustar um modelo não compensa sem base de comprovantes | Decisão contrária |
| E2·3 MCP | O Controlei não expõe MCP | Fora do escopo |

## Pendências (em ordem de valor)

1. **Rotacionar o `JWT_SECRET`** de qualquer ambiente que usou o valor antigo (ação do dono).
2. **Testcontainers (Postgres, Kafka, Redis):** rodar as migrations reais (V21–V23, incluindo `SKIP LOCKED`, `IDENTITY` e índices parciais), o relay e o consumidor com broker de verdade, e aposentar o schema H2 duplicado. **Hoje essas partes só foram verificadas contra H2 e dublês.**
3. Teste de ponta a ponta com a stack no Docker (nunca rodou: o Docker não estava ligado).
4. Primeiro CI verde no GitHub (o workflow foi criado, mas ainda não rodou).
5. Mover a regra de parcelas do cartão e do orçamento para o domínio, com testes puros; tirar a validação Jakarta dos DTOs de `domain`.
6. Dashboard sem N+1 por membro, paginação das demais listagens, cache de dashboard.
7. Leitura de PDF pelo modelo; avaliação da extração com comprovantes reais, no formato da [spec 05](05-avaliacao.md).
8. Open Finance real, ou remover a transação de exemplo.
9. TLS e HSTS no ponto de entrada público; cookie `HttpOnly` com BFF no lugar do `localStorage`.
10. `OnPush` e cancelamento de requisições no front.

## Decisões em aberto

- Qual modelo usar na leitura de imagem em produção. Hoje é configurável (`CONTROLEI_AI_MODEL`); falta medir custo e acerto numa bateria.
- Cota de IA em memória (por instância) ou no Redis, se houver mais de uma instância.

## Decisões tomadas

| Decisão | Alternativa | Motivo |
|---|---|---|
| O modelo só sugere; o código valida e o humano confirma | Criar a transação direto da resposta | É dinheiro. Um erro silencioso custa mais do que um clique |
| IA desligada por padrão | Ligada | O comprovante tem dado pessoal e sai do servidor |
| Regras (regex) como plano B | Falhar quando a IA falha | O sistema não pode depender de um provedor externo |
| Sem IA, imagem volta como "revisar" | Manter o texto fixo de exemplo | Dado inventado num portfólio é pior que funcionalidade ausente |
| Porta `ReceiptAiClient` no domínio | Chamar o OpenRouter direto no serviço | Troca de provedor sem mexer na regra; teste com cliente falso |
| Chamada ao modelo fora da transação | Dentro, por simplicidade | Conexão do banco presa durante uma chamada externa esgota o pool |
| Transactional Outbox | `@TransactionalEventListener(AFTER_COMMIT)` | AFTER_COMMIT perde o evento se o processo cair entre o commit e o envio; o outbox não |
| Outbox com relay por polling | CDC (Debezium) | CDC é mais eficiente, mas é mais uma peça para operar; polling a cada 1 s basta nesta escala |
| Kafka para os eventos | RabbitMQ | Replay do log e prática com a ferramenta. Para esta escala, RabbitMQ bastaria (registrado no portfólio) |
| Algoritmos de rateio no domínio, como classes puras | Manter nos services | Testáveis por propriedade em milissegundos, sem Spring |
| ArchUnit como parte do build | Só documentar a arquitetura | A regra já tinha sido violada sem ninguém notar |
| Segredos sem valor padrão em produção | Padrão no Compose | Padrão conhecido é segredo vazado |
| Hash simples (SHA-256) para refresh token | BCrypt | O token tem 256 bits aleatórios; hash lento só ajuda segredos de baixa entropia |
| Tirar o Keycloak do Compose | Manter "para o futuro" | Serviço exposto, com senha padrão e sem uso, é só superfície de ataque |
