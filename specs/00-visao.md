# 00 · Visão

**Status:** rascunho · **Atualizada em:** 2026-10-02

## Em uma frase

O JavAI é um serviço em Java 25 que responde perguntas sobre o Gilvan Borges com evidência tirada do código público dele. Quem consome é o portfólio e qualquer assistente de IA via MCP.

## O problema

Um recrutador lê um currículo em segundos e quase nunca abre um repositório. Um tech lead até abre, mas não tem tempo de procurar onde está a prova de cada afirmação. No fim, os dois confiam no que o candidato escreveu sobre si.

## A proposta

Toda resposta do JavAI sai de uma de duas fontes:

1. **O conteúdo cadastrado e publicado no CRUD:** experiências, projetos, formação, perguntas frequentes.
2. **Os repositórios públicos do GitHub que o Gilvan escolher,** lidos direto do código.

Se a pergunta é sobre uma habilidade, a resposta aponta o arquivo que a comprova. Se não há prova, ela diz isso: "não encontrei nos repositórios públicos".

## Por que Java 25

O modelo de linguagem roda no OpenRouter, e tanto faz em que linguagem ele foi feito. O que o JavAI quer mostrar é que todo o resto pode ser escrito em Java moderno, e bem escrito. Esse "resto" é justamente a parte que decide se uma IA é confiável:

- **Embeddings gerados dentro da JVM,** na própria VPS. Nenhum texto sai do servidor para virar vetor.
- **Busca híbrida (BM25 + cosseno + RRF) implementada no projeto,** sem banco vetorial.
- **Cosseno calculado com a Vector API (SIMD),** com benchmark JMH comparando com a versão escalar.
- **Virtual threads** para o streaming e para as chamadas em paralelo.
- **Scoped Values** para carregar o contexto da requisição.
- **A API de KDF do JDK** para derivar as chaves de assinatura.
- **A bateria de ataques escrita como teste JUnit 5.**

O modelo de linguagem só escreve a resposta. O que ele lê, se a pergunta é aceita e se a resposta pode sair: tudo isso é decidido por código Java.

## Quem usa

| Consumidor | Por onde chega | O que precisa |
|---|---|---|
| Recrutador | Chat no portfólio | Respostas curtas, em português ou inglês, com link para a prova |
| Tech lead | Chat no portfólio | Evidência no código: repositório, arquivo, linha |
| Assistente de IA (Claude, ChatGPT etc.) | Servidor MCP | Ferramentas só de leitura, com dados estruturados |
| Portfólio, no build | Snapshot estático publicado pelo CRUD | O conteúdo publicado, para montar as páginas |
| Gilvan | Swagger UI do admin (CRUD) | Cadastrar, revisar e publicar |

O repositório tem dois serviços isolados, o **CRUD** e a **IA**, sem código, banco nem rede de dados em comum ([01](01-arquitetura.md#a-regra-de-isolamento)).

## Objetivos

1. Responder sobre perfil, projetos, experiência e habilidades, sempre com a fonte.
2. Ligar cada habilidade às evidências encontradas no código (manifestos, dependências, arquivos). Esse cálculo não usa LLM.
3. Ser a fonte única do conteúdo: o que está no CRUD alimenta a IA e também as páginas do portfólio.
4. Deixar o Jev escolher o modelo de cada resposta, sempre de uma lista fixa, e mostrar essa escolha.
5. Resistir a abuso: prompt injection, gasto sem limite, vazamento de conteúdo privado.
6. Publicar a própria avaliação: a bateria, os resultados e o custo.

## O que fica de fora

- **Front-end.** O JavAI não tem interface. O portfólio fica num repositório separado.
- **Repositórios privados.** A IA só lê repositórios públicos ligados no CRUD. Um projeto privado entra como descrição cadastrada, nunca como código.
- **Issues, pull requests e comentários do GitHub,** porque qualquer pessoa escreve neles.
- **Ações.** O JavAI não escreve código, não manda e-mail e não agenda nada. Ele só lê e responde.
- **Guardar perguntas.** Ficam só contadores agregados.
- **Chat genérico.** Pergunta que não é sobre o Gilvan recebe recusa.

## Princípios

1. **Evidência ou "não encontrei".** Nenhuma lacuna é preenchida com suposição.
2. **A IA não vaza o que não consegue ler.** O conteúdo privado fica no banco do CRUD, que a IA não alcança nem pela rede. A IA só recebe o snapshot público.
3. **Nada secreto no prompt.** Se o prompt vazar, ninguém ganha nada com isso.
4. **O modelo nunca escolhe nada que custe dinheiro ou dê acesso:** nem o próprio modelo, nem consultas, nem ferramentas.
5. **Medir antes de decidir.** Modelo, prompt e busca mudam com base na bateria de avaliação, não por impressão.
6. **Spec antes do código.**

## Fases

| Fase | Entrega | Usa LLM? |
|---|---|---|
| 0 | Estas specs | Não |
| 1 | **CRUD:** banco do admin com papéis e RLS testados, admin-api com CRUD e Swagger UI atrás do Cloudflare Access, publicação do snapshot estático para o portfólio, docker-compose da VPS | Não |
| 2 | **Fundação da IA:** banco da IA, `ai-core`, `ai-indexer` (snapshot e GitHub), evidências de habilidades, embeddings locais, busca híbrida e avaliação da busca | Não |
| 3 | Servidor MCP só de leitura (`ai-api`) | Feita e no ar (`POST https://api.gilvanborges.com.br/mcp`) |
| 4 | Bateria de ataques (escrita antes do prompt), chat com OpenRouter, roteador Jev, guardas, Turnstile | Código e bateria de defesas no ar; o chat responde 503 de manutenção até as chaves e os modelos serem configurados ([05](05-avaliacao.md#estado-da-fase-4)) |
| 5 | Painel público, comparação de modelos, notas técnicas | Sim |

As três primeiras fases não gastam nada com modelo e já entregam valor: o portfólio passa a ler o conteúdo do CRUD, e o MCP já responde com evidência.

## Antes de pôr a IA no ar: arrumar os repositórios

A IA responde com base no código, não no README. Na análise de 2 de outubro de 2026, os pontos abaixo fariam a IA contradizer o perfil:

- [ ] `FightConnect2025/apiSuporte`: tem só 1 arquivo de teste e nenhuma migração Flyway, mas o README do perfil cita Testcontainers e Flyway.
- [ ] `FightConnect2025/fightconnect-worker-notifications`: aparece em destaque no README do perfil e retorna 404.
- [ ] `ordens-servico-campo`: só tem o README, embora a descrição prometa Java 21 e DDD.
- [ ] `webPonto`: tem 30.497 arquivos de `node_modules` e a pasta `.angular` commitados, e não tem `.gitignore`. Com o histórico, passa de 60 MB, acima do limite do indexador ([01](01-arquitetura.md#indexador-ai-indexer)). Correção:
  - remover `node_modules` e `.angular` do histórico com `git filter-repo` e fazer push forçado (antes, combinar com quem usa o branch `matheus-dev`);
  - depois, adicionar o `.gitignore`.
- [ ] `formacao-coti` (apiHistoricos, apiUsuarios) e `desafioWebApi`: têm senhas escritas no `application.properties`. Trocar por variáveis de ambiente.
- [ ] `KaioMuniz`: é fork do repositório de outra pessoa e não deve ser ligado no CRUD.

## Como saber que deu certo

- Toda resposta factual traz pelo menos uma fonte.
- A bateria tem 100% de acerto nas categorias de segurança, e a guarda não barra nenhuma pergunta legítima ([05](05-avaliacao.md)).
- O custo médio por resposta e o gasto do mês aparecem no painel e ficam abaixo dos tetos de [01](01-arquitetura.md#custos-e-limites).
- A partir de uma resposta, um tech lead chega em um clique ao arquivo que prova a afirmação.

## Decisões tomadas

- **Idiomas (2026-10-02):** português e inglês desde a Fase 1. O CRUD, o snapshot e o MCP já nascem bilíngues.
- **CRUD e IA isolados no mesmo repositório (2026-10-02):** nada de código, banco ou rede de dados compartilhados. O indexador fica do lado da IA, e o CRUD não sabe que a IA existe.
- **Publicar, no CRUD:** gera o snapshot em pt e en e o exporta como arquivo estático. A IA o lê como qualquer fonte pública.
- **Módulos criados quando houver uso:** os módulos `ai/*` nascem na Fase 2, e `ai/evals` na Fase 4.
