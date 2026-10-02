# Specs do JavAI

O JavAI segue Spec-Driven Development. Toda mudança de direção entra primeiro numa spec e só depois vira código.

| # | Spec | Assunto | Status |
|---|---|---|---|
| 00 | [Visão](00-visao.md) | Problema, público, objetivos, o que fica de fora, fases | Rascunho |
| 01 | [Arquitetura](01-arquitetura.md) | Módulos, fluxo do chat, roteador, busca, indexador, infraestrutura, deploy | Rascunho |
| 02 | [Dados e acesso](02-dados-e-acesso.md) | Schemas, papéis do PostgreSQL, permissões, RLS, testes de acesso | Rascunho |
| 03 | [Contrato da API](03-contrato-api.md) | Endpoints públicos, MCP e admin; eventos do streaming; erros | Rascunho |
| 04 | [Ameaças](04-ameacas.md) | Ativos, atores, OWASP LLM Top 10, infraestrutura, resposta a incidente | Rascunho |
| 05 | [Avaliação](05-avaliacao.md) | Bateria, verificador, juiz, busca, guarda, modelos, critérios para lançar | Rascunho |

## Status de uma spec

- **Rascunho:** em discussão; pode mudar inteira.
- **Aprovada:** o código pode ser escrito a partir dela.
- **Implementada:** o código existe e os testes citados na spec passam.
- **Substituída:** uma spec mais nova tomou o lugar dela. O texto antigo fica no repositório.

## Decisões em aberto

Cada spec termina com as suas. Nenhuma trava a Fase 1. As que travavam foram decididas em 2026-10-02: domínios `api.` e `admin.`, português e inglês desde o início, e colunas `_pt` e `_en`.

As restantes só precisam estar fechadas antes da fase em que aparecem. A do Jev em produção, por exemplo, só trava a Fase 4.
