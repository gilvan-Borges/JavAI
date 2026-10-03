# Specs do JavAI

O JavAI segue Spec-Driven Development. Toda mudança de direção entra primeiro numa spec e só depois vira código.

| # | Spec | Assunto | Status |
|---|---|---|---|
| 00 | [Visão](00-visao.md) | Problema, público, objetivos, o que fica de fora, fases | Aprovada |
| 01 | [Arquitetura](01-arquitetura.md) | Isolamento entre CRUD e IA, módulos, publicação, indexador, chat, infraestrutura, deploy | Implementada |
| 02 | [Dados e acesso](02-dados-e-acesso.md) | Os dois bancos (CRUD e IA), papéis, permissões, RLS, testes de acesso | Implementada |
| 03 | [Contrato da API](03-contrato-api.md) | Snapshot do conteúdo, API da IA, MCP e admin; eventos do streaming; erros | Implementada |
| 04 | [Ameaças](04-ameacas.md) | Ativos, atores, OWASP LLM Top 10, infraestrutura, resposta a incidente | Implementada |
| 05 | [Avaliação](05-avaliacao.md) | Bateria, verificador, juiz, busca, guarda, modelos, critérios para lançar | Implementada |
| 06 | [Base acadêmica](06-base-academica.md) | As duas ementas de pós como régua: mapa tema → código, divergências e pendências | Aprovada |

## Status de uma spec

- **Rascunho:** em discussão; pode mudar inteira.
- **Aprovada:** o código pode ser escrito a partir dela.
- **Implementada:** o código existe e os testes citados na spec passam.
- **Substituída:** uma spec mais nova tomou o lugar dela. O texto antigo fica no repositório.

## Decisões em aberto

Cada spec termina com as suas. Nenhuma trava a Fase 1. As que travavam foram decididas em 2026-10-02: domínios `api.` e `admin.`, português e inglês desde o início, e colunas `_pt` e `_en`.

As restantes só precisam estar fechadas antes da fase em que aparecem. A do Jev em produção, por exemplo, só trava a Fase 4.
