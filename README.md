# JavAI

Responde perguntas sobre o Gilvan Borges com evidência tirada do código público dele, em Java 25. É consumido pelo portfólio [gilvanborges.com.br](https://gilvanborges.com.br) e por assistentes de IA via MCP.

O repositório tem dois serviços isolados, sem código, banco ou rede de dados em comum:

| Pasta | Serviço | Estado |
|---|---|---|
| [`admin/`](admin) | **CRUD**: cadastro do conteúdo, publicação do snapshot e auditoria | Fase 1 |
| `ai/` | **IA**: indexador, chat e MCP | A partir da Fase 2 |
| [`infra/`](infra) | docker-compose, Caddy e o guia de deploy na VPS | Fase 1 |
| [`specs/`](specs/README.md) | As specs. Toda mudança começa aqui | — |

## Rodar os testes

Requer Java 25 e Maven 3.9. Os testes de banco usam Testcontainers e precisam do Docker no ar; sem ele, são pulados.

```bash
mvn verify
```
