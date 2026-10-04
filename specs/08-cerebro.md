# 08 · O cérebro e a IA que não chuta

**Status:** aprovada (backend implementado; a página do portfólio é a etapa seguinte) · **Atualizada em:** 2026-10-04

## Por que existe

Duas coisas, uma motivando a outra.

1. **A IA errou de um jeito que a bateria não pegava.** Perguntado "qual foi o último commit dele?", o chat respondeu com um repositório de 01/10, quando o dono tinha commitado no próprio dia em outro. A pergunta não cita repositório; a busca por significado devolveu alguns resumos soltos, e o modelo escolheu "o mais recente" entre os poucos que viu. É um erro de **desenho do índice**, não do modelo.
2. **O dono quer mostrar o que a IA sabe e por onde as pessoas andam**, como um mapa de calor do conhecimento, sem expor o que ninguém perguntou. Referência: o painel, o mapa do conhecimento e a avaliação do Nox (marcusrdrigues.com/pt/nox), e o [noxeval](https://github.com/marcusrdrigues/noxeval) de onde vieram as ideias de avaliação.

## Parte 1 · A IA que não chuta

| Mudança | Onde | Por quê |
|---|---|---|
| **Documento de atividade** | `RepoActivity` (indexador) | Um trecho único, ordenado do commit mais recente para o mais antigo, em pt e en, com a resposta na primeira frase. Perguntas que atravessam repositórios passam a ter um trecho que já os compara |
| **Dossiê por repositório** | `GitHubRepoSource.facts`, `RepoChunker` | Últimos 10 commits (o clone passou a ter profundidade 10, então não há chamada extra à API do GitHub), tópicos, branch, licença e datas, com o nome do repositório no título e o link para a página de commits (o arquivo sintético não existe no GitHub). Mensagem de commit que parece segredo não derruba o dossiê: ele sai só com datas e hashes |
| **Âncora de atividade** | `ActivityIntent`, `ChatService.withActivity` | Detector determinístico (pt/en, tolera "comit", acento e caixa) que põe o documento de atividade na frente das fontes, como o perfil já entra nas perguntas de perfil. Não é categoria do Jev: não muda o que o classificador responde nem exige recalibrá-lo |
| **Prompt v2, regra 8** | `PromptBuilder` | Superlativos e comparações ("o mais recente", "o maior") só se uma fonte afirma a comparação; nunca ranquear as poucas fontes que o modelo vê. A mudança de versão invalida o cache de respostas |
| **Casos de regressão** | `evals/casos.json` | 6 casos: atividade recente (pt, en, com o erro de digitação "comit") e comparações que não podem ser inventadas |
| **Repetição e Wilson** | `evals/run.mjs`, `verificacoes.mjs` | `--repeat N`: falha de segurança em qualquer tentativa reprova o caso; falha de utilidade pesa contra uma taxa mínima; relatório com o limite inferior de Wilson e a lista de casos instáveis (ideia do noxeval) |

**Limite honesto:** a bateria ao vivo precisa da instância de avaliação na VPS (`evals/eval-instance.sh`), então os casos novos foram escritos e as verificações testadas, mas a rodada com o modelo real é um passo seguinte.

## Parte 2 · O cérebro

### O que é

Um mapa 2D de **origens de conhecimento públicas** (um README, uma seção, um projeto). Pontos próximos falam de coisas parecidas. Sobre ele, o calor mostra por onde a busca andou, e as ligações mostram o que costuma ser escolhido junto ("Controlei → arquitetura, assistente de IA, deploy"). É o mapa do conhecimento do Nox com uma camada de uso.

### O que é gravado, e o que nunca é

| Gravado | Nunca gravado |
|---|---|
| Por dia: quantas vezes cada **origem** do índice foi escolhida pela busca | O texto da pergunta e o da resposta |
| Por dia: quantas vezes **cada par de origens** foi escolhido na mesma resposta | IP, cookie, horário, qualquer identificador |
| | A sequência de uma conversa: não há como remontá-la a partir dos pares |
| | Os trechos fixos (perfil, experiência), que entram em toda resposta de perfil e não dizem nada sobre interesse |

**A privacidade está no formato do dado.** A interface `HeatStore` só recebe `(dia, origens)`; as colunas são `source_ref` (limitada a 300 caracteres, o que não deixa passar uma pergunta colada), `hits` e `day`. Os testes provam que tudo o que é gravado existe no índice público e que um texto-isca na pergunta nunca chega ao contador.

### Banco (V7)

`ops.knowledge_heat (day, source_ref, hits)` e `ops.knowledge_pair (day, source_a, source_b, hits)`, com `source_a < source_b` (`COLLATE "C"`, a mesma ordem do Java: sem isso, "Zeta" e "alfa" seriam recusados pelo idioma do banco). Só o papel `ai_reader` lê e soma (`SELECT, INSERT, UPDATE`); ninguém apaga. RLS ligado e forçado. O indexador não tem acesso.

A chave é a **origem estável** (`source_ref`), e não o id do trecho, que muda a cada versão do índice.

### Regras de exibição

1. **Mínimo de 3.** Um trecho ou par só aparece com número se foi escolhido ao menos 3 vezes na janela. Com pouco tráfego, um número pequeno pode ser uma visita só.
2. **Hoje não entra.** Com o padrão de 1 dia, o dia mais recente fica de fora, então dois retratos seguidos nunca revelam o que uma pessoa acabou de perguntar.
3. **Janela de 30 dias**, mapa reaproveitado por 60 minutos, resposta com `Cache-Control` público de 10 minutos.
4. **Ligações:** só pares com o mínimo e só entre pontos que existem no índice atual (no máximo 80).

Tudo é configurável em `aiapi.brain.*` (`min-hits`, `window-days`, `delay-days`, `cache-minutes`, `probe-per-minute`).

### Layout

PCA em duas dimensões, em Java puro (iteração de potência com ponto de partida fixo, então o mapa é sempre o mesmo para o mesmo índice), sobre a média dos vetores dos trechos de cada origem. Os dois eixos usam a mesma escala: esticar cada um por conta própria faria o ruído do segundo componente parecer tão grande quanto a diferença entre assuntos. Sem vetores (índice só com BM25), cada grupo ocupa uma fatia do círculo, de forma determinística. O layout é calculado uma vez por versão do índice.

### API

| Método e caminho | Para quê |
|---|---|
| `GET /v1/brain` | `nodes` (id, título, tipo, grupo, x, y, hits ou `null`), `groups`, `branches` (grupos com calor e os 5 pontos mais escolhidos), `edges`, janela e mínimo. Sem índice, 503 |
| `POST /v1/brain/probe` | "Onde minha pergunta cai?": só a busca (sem modelo de chat, sem custo), devolve as origens que ela escolheria, em ordem. **Não grava nada**, nem em log. 3 a 200 caracteres, 10 por minuto por visitante (hash do IP com a chave do dia, só em memória), `no-store` |

Ambos usam o CORS que o chat já tem para o portfólio, e o Caddy roteia `/v1/brain*` para o ai-api.

### Ameaças consideradas

| Ameaça | Resposta |
|---|---|
| Descobrir o que uma pessoa específica perguntou | Não existe o texto; só origens públicas. O mínimo de 3 impede que uma visita isolada acenda um ponto, e o atraso de 1 dia impede comparar retratos consecutivos |
| Inferir uma conversa pela combinação de trechos | Só há contadores de par por dia; não há como ligar pares a uma conversa. Pares abaixo do mínimo não aparecem |
| Usar a sonda para vasculhar o índice | O índice já é público; a sonda custa só CPU local e tem limite por visitante |
| Inflar o calor de propósito | Cada resposta conta no máximo 8 origens e 28 pares; o chat já tem Turnstile, limite por IP e teto de gasto |
| Quebra do contador derrubar o chat | A gravação é isolada: erro vira aviso no log e a resposta segue |

## Decisões

| Decisão | Alternativa descartada | Por quê |
|---|---|---|
| Contar a origem escolhida pela busca | Classificar o tema da pergunta (ou guardar a pergunta) | O tema vem do trecho, não do texto: é o que o Nox faz, e o dado não tem como identificar ninguém |
| Mínimo de 3 e atraso de 1 dia | Mostrar tudo em tempo real | Com pouco tráfego, tempo real mostra o interesse de uma pessoa |
| Âncora de atividade determinística | Nova categoria no Jev | Não exige recalibrar o classificador nem arrisca barrar pergunta legítima |
| Dossiê com os últimos 10 commits via clone raso de profundidade 10 | Chamar a API do GitHub por repositório | O limite sem token é de 60 por hora; o clone já existia |
| Mesma escala nos dois eixos da PCA | Normalizar cada eixo | Evita transformar ruído em "assunto" |

## Pendências

- A **página do portfólio**: mapa com calor, ramificações por projeto e a sonda (a pergunta cai no mapa e as origens escolhidas acendem).
- Rodar a bateria ao vivo com `--repeat` e publicar o placar.
- Do noxeval, ainda não portado: juiz calibrado (Jev), erros plantados, revisão cega com kappa e verificação de trajetória do agente.
- Retenção: os contadores crescem pouco (linhas por dia e por origem), mas uma rotina com o papel dono para apagar o que passou de um ano seria prudente.
