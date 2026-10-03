# Segurança

O JavAI é um serviço público e security-first: o modelo de ameaças está na [spec 04](specs/04-ameacas.md), e as defesas (isolamento, RLS, guardas de entrada e saída, limites, banimento) estão descritas na [spec 01](specs/01-arquitetura.md).

## Reportar uma vulnerabilidade

**Não abra issue pública** para falha de segurança.

- Use **GitHub Security Advisories**: aba *Security → Report a vulnerability* deste repositório (relato privado).
- Descreva o impacto, os passos para reproduzir e, se tiver, uma prova de conceito.

Compromisso: resposta em até **72 horas** e, confirmada a falha, correção publicada pelo CI/CD antes de qualquer divulgação. Crédito no changelog se você quiser.

## Escopo

- Em escopo: `api.gilvanborges.com.br` (chat, MCP, `/admin`), o código deste repositório e o fluxo de deploy.
- Fora de escopo: negação de serviço volumétrica (há CDN/limites), engenharia social, e relatórios de scanner sem prova de exploração.

## O que não guardamos

Perguntas e respostas do chat não são gravadas em banco ou log; IPs viram hash com chave rotativa. Isso limita o impacto de qualquer vazamento, mas relate mesmo assim.
