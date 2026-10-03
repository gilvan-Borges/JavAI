# 06 · Base acadêmica (as duas ementas)

**Status:** aprovada · **Atualizada em:** 2026-10-03

## O que esta spec é

O JavAI segue duas ementas de pós-graduação como régua de qualidade:

1. **Engenharia de Software com Java + IA** (UNIPDS) — Java moderno, Spring Boot, arquitetura, concorrência, infra, bancos, testes.
2. **Engenharia de Software em IA Aplicada** (UNIPDS) — LLMs, APIs de IA, MCP, agentes, arquitetura AI-first, fine-tuning, segurança e governança.

Elas são **a base de tudo**: toda decisão técnica do projeto precisa ou seguir um tema das ementas ou registrar aqui por que se afasta dele. Quando o projeto virar open source, esta spec é o mapa que mostra onde cada tema está no código.

Os PDFs ficam fora do Git (são material da instituição, com marca e contato). O dono guarda as cópias na raiz do clone; o Git as ignora.

## Como ler as tabelas

- **Atende:** implementado e com teste ou verificação em produção.
- **Parcial:** existe, mas falta uma parte, listada em "Pendências".
- **Decisão contrária:** o projeto escolheu NÃO seguir o tema, com o porquê registrado.
- **Fora do escopo:** o tema não se aplica a um serviço deste tamanho; entra se o projeto crescer.

## Ementa 1 — Java + IA

| Módulo da ementa | No JavAI | Estado |
|---|---|---|
| 1. Fundamentos de Java (collections, OO, streams, lambdas, NIO, exceções, Maven) | Java 25 com records, sealed, streams e NIO em todo o código; exceções com mensagens sem segredo; Maven multimódulo (`admin/`, `ai/`) | Atende |
| 2. Spring Boot, REST, OAuth2/JWT, JPA | Spring Boot 4 nos quatro serviços; REST com validação estrita; sessão assinada (HMAC) no admin; OIDC no admin-api; JDBC direto no lado da IA (decisão: sem JPA onde o SQL é simples e o RLS manda) | Atende |
| 2. Quarkus e GraalVM | Um só framework para o projeto inteiro ficar legível | Decisão contrária |
| 2. IA corporativa com LangChain4j | RAG, embeddings, chunking e busca **implementados no projeto**, sem framework de IA: o objetivo é mostrar o mecanismo, não escondê-lo. A ementa pede entender a arquitetura; aqui ela está exposta | Decisão contrária (documentada em 00-visão) |
| 2. RAG, embeddings e busca semântica | e5-small int8 via ONNX **dentro da JVM**; BM25 próprio + cosseno SIMD + RRF; ingestão do CRUD, do GitHub e das fontes públicas | Atende |
| 2. Agents e tools | Jev (System 1) classifica e decide recusa/banimento; o modelo principal responde; tools expostas via MCP | Atende |
| 2. MCP | Servidor MCP público (`/mcp`, Streamable HTTP stateless, SDK Java 2.0.1) | Atende |
| 2. Segurança, resiliência e produção em IA (prompt injection, guardrails, cache, retries, timeouts, observabilidade) | Prompt delimitado com canário; output guard que retrata; Turnstile + limites + banimento; timeouts em toda chamada externa; fallback de modelos; evento `trace` por resposta; `/v1/stats` e `/admin` | Parcial (ver Pendências) |
| 3. Front-end React | Portfólio em React 19 + Vite + Tailwind, com o terminal do JavAI | Atende |
| 4. Arquitetura (camadas, clean, event-driven, CQRS) | Separação dura CRUD × IA (bancos, redes e código separados); dependências validadas por ArchUnit; publicação por snapshot (o CRUD não chama a IA); NOTIFY do Postgres no lugar de broker — um broker para um produtor e um consumidor seria peso morto | Atende (CQRS/broker: fora do escopo) |
| 5. Design patterns, DDD, System Design | Bounded contexts explícitos (conteúdo × índice × chat); agregados no CRUD; trade-offs registrados nas "Decisões tomadas" de cada spec; LB e cache de borda via Cloudflare | Atende |
| 6. Concorrência (threads virtuais, paralelismo, locks) | Threads virtuais em tudo; Jev e busca correm em paralelo na pergunta; `AtomicBoolean`/`AtomicReference` no indexador; Vector API (SIMD) no cosseno com benchmark JMH | Atende |
| 7. Infra (Docker, K8s, AWS, CI/CD) | Docker Compose endurecido (redes segregadas, tmpfs, no-new-privileges); CI/CD GitHub Actions com testes, Trivy e deploy por Tailscale OIDC; K8s/AWS: uma VPS basta para a escala real | Atende (K8s: fora do escopo) |
| 8. Bancos (modelagem, Flyway, NoSQL) | Dois Postgres; Flyway com migração imutável; papéis de privilégio mínimo + RLS com FORCE; NoSQL: o relacional cobre, e o índice vive em memória | Atende |
| 9. Testes (TDD, JUnit, AssertJ, Testcontainers, ArchUnit, análise estática, benchmark) | JUnit 5 + AssertJ em todos os módulos; Testcontainers para banco e indexador; ArchUnit nas fronteiras; bateria de avaliação real (`evals/`, 62/62); JMH no cosseno | Parcial (ver Pendências) |
| 10–11. Carreira, LinkedIn, entrevistas | É o propósito do produto: o JavAI responde recrutador com evidência; perguntas de vaga recebem perfil + experiências + projetos | Atende |

## Ementa 2 — IA Aplicada

| Módulo da ementa | No JavAI | Estado |
|---|---|---|
| 1. Fundamentos de LLMs e embeddings | Embeddings próprios (e5-small int8); prompt com delimitadores; specs explicam o fluxo | Atende |
| 2. APIs de IA generativa, custo e consistência | OpenRouter com `models` (fallback), `provider.data_collection=deny`, `max_price`, orçamento diário e contadores de custo por dia; respostas citam fonte obrigatoriamente | Atende |
| 2. Redução de alucinação | RAG com trechos literais; resposta sem citação válida é retratada pelo output guard | Atende |
| 3. MCP (criação, segurança, governança) | MCP público read-only; rate limit por IP; `Origin` restrito; WAF/CDN Cloudflare; nenhum segredo atravessa | Atende |
| 4. Agentes (padrões, memória, guardrails, HITL, runaway) | Agente de decisão (Jev) com limiares calibrados; histórico assinado (HMAC) com tamanho limitado; human-in-the-loop no `/admin` (apagar/buscar exigem sessão e confirmação); limites por IP e banimento previnem abuso de API | Atende |
| 5. IA para UX/UI | Terminal com trace ao vivo no portfólio; paleta de comandos | Atende |
| 6. IA para DevOps (scan, pipelines, rollback) | Trivy no CI (falha com CVE alta corrigível); deploy com rollback automático; FinOps: orçamento diário e custo por resposta medidos | Atende |
| 8. Arquitetura AI-first (decision framework, model router, RAG híbrido, caching, HITL) | Framework de decisão IA × regra determinística: Turnstile/limites/assinaturas são regras; Jev decide o ambíguo; modelo forte/rápido por intenção (tiers); RAG híbrido (BM25 + vetor + RRF) | Parcial (caching semântico pendente) |
| 9. Fine-tuning (decision framework, datasets, LoRA, avaliação) | **Decisão contrária:** o framework da própria ementa manda usar RAG quando o conhecimento muda com frequência e precisa de fonte citável — exatamente este caso. O `/admin` declara "Treinamento: nenhum". A bateria de avaliação (spec 05) cumpre o papel de avaliação de modelo | Decisão contrária |
| 10. Segurança e governança (riscos, vieses, custos, legal) | Spec 04 (OWASP LLM Top 10, atores, resposta a incidente); e-mail e telefone são removidos das fontes antes de indexar; custos medidos e limitados; dados pessoais só os públicos do dono | Atende |
| 11. Capstone (micro-SaaS com RAG + agentes + MCP + front + CI/CD + documentação) | O JavAI é o capstone: RAG próprio, agente de decisão, MCP, front integrado, CI/CD e estas specs. Falta o acabamento de open source (ver Pendências) | Parcial |
| 12. Carreira para engenheiro de IA | Mesmo papel do módulo 10–11 da ementa 1 | Atende |

## Pendências (o que as ementas pedem e ainda falta)

Em ordem de valor:

1. **Retries com backoff no OpenRouter** (ementa 1, módulo 2; ementa 2, módulo 2): hoje há fallback de modelo e retratação, mas uma falha transitória de rede não é retentada.
2. **Cache de respostas/prompt** (ementa 2, módulo 8): perguntas idênticas repetidas pagam o modelo de novo. Avaliar cache por hash da pergunta normalizada + versão do índice.
3. **Observabilidade estruturada** (ementa 1, módulo 2): o `trace` por resposta existe; faltam métricas agregadas de latência por etapa (p50/p90) expostas no `/admin`.
4. **Análise estática no CI** (ementa 1, módulo 9): ligar SpotBugs/PMD (ou ErrorProne) no build.
5. **Acabamento open source** (ementa 2, capstone): README de raiz com diagrama de arquitetura, instruções de subir local e `CONTRIBUTING.md`; conferir que nenhum valor pessoal reste fora de configuração.
6. **Warm-up do embedder no boot** (qualidade de produção): a primeira busca após reinício paga ~6 s.

## Decisões tomadas

| Decisão | Alternativa | Motivo |
|---|---|---|
| Ementas como régua, registradas nesta spec | Seguir implícito, sem documento | Open source precisa de critério visível para aceitar mudanças |
| PDFs fora do Git | Comitar os PDFs | Material da instituição; o resumo em texto próprio basta |
| Divergências viram "Decisão contrária" com motivo | Implementar tudo da ementa | A ementa ensina a decidir; copiar tudo (Quarkus + Spring, NoSQL sem uso) seria o erro que ela mesma aponta |
