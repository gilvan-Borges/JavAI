# 07 · Controlei (finanças familiares com IA nos comprovantes)

**Status:** aprovada · **Atualizada em:** 2026-10-04

Repositório próprio: <https://github.com/gilvan-Borges/controlei>. Esta spec é a régua dele, no mesmo formato das specs do JavAI, e liga cada decisão às duas ementas ([06](06-base-academica.md)).

## Em uma frase

O Controlei é um sistema de finanças para uma família, em Java 25 e Angular 21, com visão individual e visão consolidada, eventos em Kafka e leitura de comprovantes por IA em que o modelo só sugere e o código valida.

## O problema

Uma família divide contas, cartão, parcelas e metas, mas cada pessoa lança seus gastos num lugar. Ninguém vê o conjunto, e o lançamento manual é a parte que as pessoas abandonam primeiro.

## O que o sistema faz

| Área | O que existe | Estado |
|---|---|---|
| Família e papéis | Uma família por usuário; `RESPONSIBLE` edita tudo, `MEMBER` edita só o que é seu | Implementado, com teste de acesso entre famílias |
| Contas, categorias, transações | CRUD com soft delete e auditoria (`created_by`, `updated_by`, `deleted_at`) | Implementado |
| Dívidas e parcelas | Parcelas geradas ao criar a dívida | Implementado |
| Cartão de crédito e faturas | Cartão, compras, fatura | Implementado |
| Recorrências, orçamentos, metas | Transações recorrentes, orçamento mensal com alerta, metas com contribuições | Implementado |
| Rateio de despesas | Divisão entre membros e liquidação ("quem deve para quem") | Implementado |
| Investimentos | Posição e movimentações | Implementado |
| Dashboard e relatórios | Visão individual e familiar, relatórios | Implementado |
| Notificações | Alertas de orçamento estourado, via evento | Implementado |
| Planos (assinatura) | Plano e cota de membros, com eventos de ativação e expiração | Implementado |
| **Leitura de comprovantes** | Imagem ou texto vira valor, data, estabelecimento e categoria sugerida | **Implementado com IA (ver abaixo)** |
| Open Finance | Sincronização bancária | **Simulado:** cria uma transação de exemplo. Não há integração com banco |
| Keycloak | Sobe no Compose, mas o código não o usa | **Não integrado.** A autenticação é JWT próprio (access + refresh), BCrypt e rate limit |

O que está simulado aparece escrito assim no README e no portfólio. Nada simulado é apresentado como real.

## Arquitetura

```
 Angular 21 ──► Nginx ──► Spring Boot 4.1 (Java 25, virtual threads)
                             ├─ domain        regras puras, sem Spring
                             ├─ application   casos de uso, controllers, DTOs
                             └─ infrastructure JPA, JWT, Kafka, Redis, IA
                                   │
        PostgreSQL 16 (Flyway) ◄───┤──► Kafka (KRaft) ──► consumidor idempotente ──► Redis (chave + lock)
                                   └──► OpenRouter (só comprovantes, só se ligado)
```

Regras que não mudam:

1. **Tudo pertence a uma família.** Toda leitura e escrita passa por `AuthorizationService`; tentar acessar outra família é teste de integração.
2. **Dinheiro é `BigDecimal`** (`DECIMAL(19,4)` no banco). Nunca `double`.
3. **Nada é apagado de verdade.** Soft delete e trilha de auditoria.
4. **Banco só muda por migration Flyway** (`V1` a `V20`), com `validate` em produção.
5. **Kafka com chave da família** como partição, para ordenar os eventos de uma família; o consumidor guarda a chave de idempotência no Redis, e o lock distribuído evita processamento duplo.

## IA: leitura de comprovantes

A mesma ideia do JavAI: o modelo escreve, o código decide.

```
imagem ou texto ──► [IA ligada?] ──não──► regras (regex) ──► sugestão
                         │sim
                         ▼
                 OpenRouter (data_collection=deny)
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
| Valor positivo e até R$ 1.000.000 | Barra negativo, zero e absurdo |
| Data entre 5 anos atrás e amanhã | Barra data impossível ou futura |
| Categoria só entre as da família, comparada pelo nome | O modelo nunca escolhe identificador; "apagar todas as contas" não vira categoria |
| Confiança do modelo limitada a 0,90, e a 0,30 sem valor | A confiança que o modelo declara não é prova |
| Texto cortado e sem caracteres de controle | Limita o que chega ao banco e à tela |
| Comprovante entre `<comprovante>` e instrução de que é dado | Defesa de prompt injection em camadas, não só no prompt |
| Falha, timeout ou JSON inválido caem nas regras | A IA é opcional; o sistema funciona sem ela |

Decisões de privacidade e custo:

- **Desligada por padrão** (`CONTROLEI_AI_ENABLED=false`): a imagem de um comprovante sai do servidor para o provedor.
- `provider.data_collection=deny` em toda chamada, timeout de 30 s, `temperature=0`, `max_tokens=800`.
- Nada do conteúdo do comprovante vai para o log, só o tipo da falha.
- O arquivo enviado não é guardado: só o texto lido e os campos extraídos.
- Sem IA, imagem e PDF **não são lidos**: voltam como `NEEDS_REVIEW`, sem dados. Antes, o sistema devolvia um texto fixo de supermercado com 95% de confiança, o que era uma simulação apresentada como leitura.
- PDF ainda não vai ao modelo (pendência).

Nada cria transação sozinho: é um humano no circuito (HITL). A tela mostra "Revisar" quando a leitura não é confiável.

## Segurança

| Item | Estado |
|---|---|
| Senha com BCrypt, JWT de 15 min + refresh token, rate limit no gateway e no filtro | Implementado |
| `JWT_SECRET` obrigatório em produção, mínimo de 32 caracteres, validado no boot | Implementado em 2026-10-04: o perfil `prod` e o Compose não têm mais valor padrão |
| Segredo antigo no histórico do Git | **Pendente:** o valor antigo continua no histórico. Precisa ser rotacionado (trocar `JWT_SECRET` em qualquer ambiente que o usou). Reescrever histórico não é necessário se o segredo for trocado |
| Isolamento entre famílias | Testado por integração |
| Upload de comprovante | Só JPEG, PNG e PDF, até 10 MB |

## Mapa para as ementas

| Tema da ementa | No Controlei | Estado |
|---|---|---|
| E1·1 Fundamentos de Java | Java 25, records, streams, `BigDecimal`, exceções tipadas | Atende |
| E1·2 Spring Boot, REST, JWT, JPA | Spring Boot 4.1, REST, Bean Validation, Spring Security, JPA + Flyway | Atende |
| E1·4 Arquitetura (clean, event-driven) | Domain / application / infrastructure; Kafka com idempotência | Atende |
| E1·5 DDD, patterns | Portas e adaptadores para repositórios; entidades ricas | Atende |
| E1·6 Concorrência | Virtual threads; lock distribuído no Redis | Atende |
| E1·7 Infra | Docker Compose (Postgres, Redis, Kafka, Nginx), imagens de produção, usuário não root | Parcial (sem CI/CD e sem deploy) |
| E1·8 Bancos | PostgreSQL, 20 migrations, soft delete, auditoria | Atende |
| E1·9 Testes | 132 testes no back (JUnit, MockMvc, H2) e 39 no front (Vitest) | Parcial (sem Testcontainers, sem Kafka real nos testes) |
| E1·3 Front-end | Angular 21 + Bootstrap, lazy loading por feature | Atende |
| E2·2 APIs de IA, custo e consistência | OpenRouter, `temperature=0`, saída JSON validada, modelo configurável | Atende |
| E2·2 Redução de alucinação | Valor tem de constar no texto; categoria só da lista | Atende |
| E2·4 Agentes, guardrails, HITL | Sem agente; guardrails de saída e confirmação humana antes da transação | Atende |
| E2·10 Segurança e governança | IA desligada por padrão, `data_collection=deny`, sem conteúdo em log | Atende |
| E2·9 Fine-tuning | **Decisão contrária:** a extração é tarefa de modelo geral com saída validada; ajustar um modelo não compensa sem base de comprovantes | Decisão contrária |
| E2·3 MCP | O Controlei não expõe MCP | Fora do escopo |

## Pendências (em ordem de valor)

1. Rotacionar o `JWT_SECRET` de qualquer ambiente que usou o valor antigo.
2. Teste de ponta a ponta com a stack no Docker (nunca rodou nesta revisão: o Docker não estava ligado).
3. ~~CI no GitHub Actions~~ — **criado (2026-10-04)** em `.github/workflows/ci.yml` (back, front e validação do Compose), com as mesmas actions fixadas por SHA do JavAI. Ainda não rodou no GitHub: só vale como feito depois do primeiro push verde.
4. Testcontainers (Postgres, Kafka, Redis) para o consumidor de orçamento.
5. Leitura de PDF pelo modelo.
6. Open Finance real, ou remover a transação de exemplo.
7. Avaliação da leitura de comprovantes com notas reais, no formato da [spec 05](05-avaliacao.md).
8. Decidir sobre o Keycloak: integrar ou tirar do Compose.

## Decisões em aberto

- Qual modelo usar na leitura de imagem em produção. Hoje é configurável (`CONTROLEI_AI_MODEL`); falta medir custo e acerto numa bateria.

## Decisões tomadas

| Decisão | Alternativa | Motivo |
|---|---|---|
| O modelo só sugere; o código valida e o humano confirma | Criar a transação direto da resposta | É dinheiro. Um erro silencioso custa mais do que um clique |
| IA desligada por padrão | Ligada | O comprovante tem dado pessoal e sai do servidor |
| Regras (regex) como plano B | Falhar quando a IA falha | O sistema não pode depender de um provedor externo |
| Sem IA, imagem volta como "revisar" | Manter o texto fixo de exemplo | Dado inventado num portfólio é pior que funcionalidade ausente |
| Porta `ReceiptAiClient` no domínio | Chamar o OpenRouter direto no serviço | Troca de provedor sem mexer na regra; teste com cliente falso |
| Kafka para os eventos | RabbitMQ | Replay do log e prática com a ferramenta. Para esta escala, RabbitMQ bastaria (registrado no portfólio) |
| Segredo do JWT sem valor padrão em produção | Padrão no Compose | Padrão conhecido é segredo vazado |
