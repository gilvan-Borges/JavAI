# JavAI

Uma IA pública, em **Java 25**, que responde perguntas sobre o dono do portfólio usando só evidência: o conteúdo que ele publicou e o código público dele no GitHub. Toda resposta cita a fonte; o que não tem prova, ela diz que não encontrou. É consumida pelo portfólio ([gilvanborges.com.br](https://gilvanborges.com.br)) e por qualquer assistente de IA via **MCP** (`https://api.gilvanborges.com.br/mcp`).

O projeto segue duas ementas de pós-graduação (Java + IA e IA Aplicada) como régua de qualidade: o mapa de cada tema para o código está na [spec 06](specs/06-base-academica.md).

## Arquitetura

Dois lados isolados de verdade — sem código, banco ou rede de dados em comum. O CRUD nem sabe que a IA existe: ele publica um snapshot estático e a IA o lê como qualquer visitante.

```
                                  ┌──────────────────────── VPS (Docker) ─────────────────────────┐
  dono ──► admin.───► Caddy ──────►  admin-api ──► postgres-admin        (rede admin-data)        │
                        │          │     └── publica /snapshot/{pt,en}.json (estático)            │
                        │          │                                                              │
  visitante ─► Cloudflare ─► Caddy │  ai-indexer ─► lê snapshot + GitHub + fontes públicas        │
   (portfólio, chat,    │          │     └── monta o índice e ativa a versão (rede ai-data)       │
    MCP clients)        └──────────►  ai-api ◄── postgres-ai                                      │
                                   │     ├── busca híbrida: BM25 próprio + e5-small (ONNX, na JVM)│
                                   │     │   + cosseno com Vector API (SIMD) + RRF                │
                                   │     ├── chat SSE: Turnstile → limites → Jev → RAG →          │
                                   │     │   OpenRouter (streaming) → guarda de saída             │
                                   │     └── MCP read-only e página /admin                        │
                                   └──────────────────────────────────────────────────────────────┘
```

Decisões que definem o projeto:

- **RAG, nunca fine-tuning:** o conhecimento muda com frequência e precisa de fonte citável. Mudou o conteúdo → o indexador reindexa; não há retreino.
- **Embeddings dentro da JVM** (ONNX + e5-small int8): nenhum texto sai do servidor para virar vetor.
- **Busca híbrida implementada no projeto** (BM25 + cosseno SIMD + RRF), sem banco vetorial: o mecanismo fica exposto, não escondido atrás de um framework.
- **Defesa em camadas no chat:** Turnstile, limites por IP, histórico assinado (HMAC), classificação de ataque (Jev), prompt com canário e guarda de saída que retrata resposta com URL, HTML ou citação inválida.
- **Privilégio mínimo no banco:** papéis separados + RLS com FORCE; a API só enxerga a versão ativa do índice.
- **Fontes configuráveis por propriedade** (`INDEXER_SOURCES_*`): site, documentos em PDF, perfil do GitHub. Quem clonar o projeto troca os endereços pelos seus.

## Estrutura

| Pasta | O que é |
|---|---|
| [`admin/`](admin) | CRUD privado: cadastro do conteúdo, publicação do snapshot, auditoria |
| [`ai/`](ai) | A IA: `ai-core` (chunking, busca, evidência), `ai-migrations`, `ai-embeddings`, `ai-indexer`, `ai-api` |
| [`infra/`](infra) | docker-compose endurecido, Caddy, guia de deploy |
| [`evals/`](evals) | Bateria de avaliação com modelos reais (respostas e segurança) |
| [`specs/`](specs/README.md) | As specs. Toda mudança começa aqui |

## Rodar local

Requer **Java 25** e **Maven 3.9+**. Os testes de banco usam Testcontainers e precisam do Docker; sem ele, são pulados (o CI os roda sempre).

```bash
mvn verify          # compila, roda os testes e a análise estática (ErrorProne)
```

Para subir tudo, copie `infra/.env.example` para `infra/.env`, preencha os valores e:

```bash
cd infra && docker compose up -d
```

## Deploy

Só pelo CI/CD (GitHub Actions): push na `main` → build, testes, Trivy, imagens no GHCR e deploy na VPS por Tailscale, com rollback automático se a saúde não voltar.

## Contribuir

Leia o [CONTRIBUTING.md](CONTRIBUTING.md). O resumo: toda mudança de direção começa numa spec, e a [spec 06](specs/06-base-academica.md) é a régua para aceitar decisões técnicas.
