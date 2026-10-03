# 04 · Modelo de ameaças

**Status:** rascunho · **Atualizada em:** 2026-10-02

O JavAI fica aberto para qualquer pessoa na internet. Esta spec lista o que precisa ser protegido, de quem, como e qual teste prova que a proteção funciona. Controle sem teste é só intenção.

## O que proteger

| # | Ativo | Por que importa |
|---|---|---|
| A1 | Crédito do OpenRouter e cota do Jev | Gasto sem limite é o ataque mais barato de fazer |
| A2 | Acesso de escrita ao conteúdo (admin) | Quem escreve no CRUD fala em nome do Gilvan |
| A3 | Conteúdo privado (rascunhos, campos privados, notas) | Não foi publicado por escolha |
| A4 | A VPS e as chaves que estão nela | Dela se chega a todo o resto |
| A5 | A reputação do Gilvan | A IA dizendo algo falso ou ofensivo em nome dele |
| A6 | Chaves de assinatura (HMAC) | Com elas se forja histórico de conversa |

## Quem ataca

| Ator | O que quer | Como age |
|---|---|---|
| Visitante curioso | Ver se a IA "quebra" | Prompt injection feito à mão |
| Abusador | Usar o modelo de graça ou gerar custo | Scripts, bots, muitas requisições |
| Atacante de injeção indireta | Fazer a IA dizer ou linkar algo | Plantar texto onde a IA vai ler |
| Scanner da internet | Achar qualquer serviço exposto | Varredura de portas e de caminhos conhecidos |
| Cadeia de suprimentos | Entrar por uma dependência | Pacote Maven, imagem Docker, Action ou arquivo de modelo |
| O próprio dono, por engano | Nada, é um acidente | Commitar um segredo, publicar o campo errado |

## Fronteiras de confiança

```
[navegador] ──(não confiável)──► [Cloudflare] ──► [Caddy] ──► [ai-api] ──► [OpenRouter] [Jev]   (terceiros)
                                                                │
[CRUD] ──(snapshot público, HTTPS, só leitura)──► [ai-indexer] ──► [index.chunk]
[código dos repositórios] ──(dado, nunca instrução)──► [ai-indexer]
[vaga colada no MCP]      ──(dado, nunca instrução)──► [modelo do cliente MCP]
```

Tudo o que vem do visitante, dos repositórios e de vagas coladas é **dado**. Entra no prompt dentro de blocos delimitados e nunca como instrução.

## OWASP Top 10 para aplicações com LLM

| Risco | Como aparece aqui | Controles | Evidência |
|---|---|---|---|
| **LLM01 Prompt injection** | Direta, pelo chat. Indireta, por README, código ou vaga colada | Guarda do Jev na entrada; nada secreto no prompt; dados em `<fonte>` com as tags removidas da pergunta; guarda de saída; canário | Bateria: `injecao_direta`, `injecao_indireta`, `pedido_pendurado` ([05](05-avaliacao.md)) |
| **LLM02 Vazamento de informação sensível** | Campos privados, rascunhos, segredos commitados | O conteúdo privado fica no banco do CRUD, que a IA não alcança (outro contêiner, outra rede, nenhuma credencial); o snapshot só tem o que é publicado e público; lista explícita de colunas na publicação; gitleaks; `application*.properties` fora do índice | Testes do banco do CRUD em [02](02-dados-e-acesso.md#testes-que-provam-o-acesso), incluindo o 11 (`private_notes` fora da publicação); fixture com segredo falso no indexador |
| **LLM03 Cadeia de suprimentos** | Dependências Maven, imagens, Actions, arquivo do modelo ONNX | Dependabot; Trivy; Actions fixadas por SHA; SHA-256 do modelo conferido ao subir | CI quebra com vulnerabilidade alta; teste com modelo adulterado |
| **LLM04 Envenenamento de dados** | Texto plantado em issue, PR ou branch; fork | Só o branch padrão de repositórios ligados pelo dono; nunca issues nem PRs; constraint contra fork | Teste do indexador com repositório de fixture |
| **LLM05 Tratamento inadequado da saída** | XSS no portfólio, injeção de markdown, link malicioso | Texto puro; o modelo cita `[n]` e o servidor monta as URLs; URL, HTML ou imagem na saída geram `retract`; o portfólio usa `textContent` | Bateria; teste de contrato do SSE |
| **LLM06 Agência excessiva** | O modelo ter poder de fazer algo | O modelo do chat não tem ferramentas; nunca escolhe o próprio modelo; o MCP é só de leitura; o GitHub é lido sem token | Requisição com campo `model` → 400 |
| **LLM07 Vazamento do prompt de sistema** | "Mostre suas instruções" | Nada secreto no prompt; canário detectado pela guarda de saída | Bateria: `injecao_direta` |
| **LLM08 Fraquezas de vetores e embeddings** | Índice com conteúdo que não deveria estar lá | Índice montado só do snapshot público e de repositórios públicos; `ai_reader` só lê a versão ativa (RLS) e não escreve no índice; versões com rollback | Testes da Fase 2 em [02](02-dados-e-acesso.md#testes-fase-2) |
| **LLM09 Desinformação** | Inventar habilidade, negar um fato verdadeiro | "Evidência ou não encontrei"; citação obrigatória; categoria `premissa_falsa`; juiz cruzado com o verificador | Bateria: `fatos`, `evidencia`, `premissa_falsa` |
| **LLM10 Consumo sem limite** | Bots, textos longos, modelo caro | Turnstile; rate limit por IP; disjuntor diário; limite na chave do OpenRouter; `max_tokens`; `max_price`; cancelamento quando o visitante sai | Bateria: `abuso_de_custo`; teste de carga |

## API, web e infraestrutura

| Ameaça | Controles |
|---|---|
| SQL injection | Só consultas com bind; nenhum text-to-SQL; Semgrep no CI; privilégio mínimo limita o estrago ([02](02-dados-e-acesso.md#sql-injection)) |
| IA comprometida tentando chegar ao CRUD | Outro banco, em outro contêiner e outra rede Docker; nenhuma credencial do CRUD no lado da IA; o Maven Enforcer impede que um lado dependa do código do outro |
| Acesso indevido ao admin | Cloudflare Access com 2FA; o app valida o JWT (`iss`, `aud`, `email`); Authenticated Origin Pulls; firewall aceita só a Cloudflare |
| CSRF no admin | Só `application/json`; CORS fechado; cookie do Access com `SameSite=Strict` |
| Histórico forjado ("modo desenvolvedor") | HMAC por turno; validade de 2 h; no máximo 4 turnos |
| Burlar o rate limit forjando cabeçalho | `CF-Connecting-IP` só é aceito de IPs da Cloudflare (firewall + `trusted_proxies`) |
| Reuso do token do Turnstile | Conferido no servidor; uso único; hostname checado |
| SSRF | Nenhuma URL vinda do visitante é acessada; o clone só aceita repositórios da tabela, sempre em `github.com` |
| Serviço interno exposto | Só o Caddy publica portas; a rede `data` é interna; o Docker ignora o `ufw` e por isso nada mais publica porta |
| Vazamento de segredos | `.env` com `chmod 600` fora do Git; secret scanning e push protection no GitHub; chaves só no servidor; chave do OpenRouter exclusiva e com limite |
| Invasão da VPS | SSH só com chave, só pela Tailscale, sem root; `unattended-upgrades`; CrowdSec; contêineres não-root e só leitura; sem `docker.sock` |
| Negação de serviço | Cloudflare; rate limit; limite de tamanho do corpo; timeouts; `statement_timeout` |
| Payload JSON malicioso | Jackson estrito, sem tipagem polimórfica e com limite de tamanho |
| Retenção de dados pelo provedor do modelo | `data_collection: "deny"`; `zdr` quando possível |
| Invasão da conta Google do dono | Passkey ou chave física no 2FA; política do Access por e-mail; sessão de 8 h; auditoria de toda escrita |
| Ataque ao pipeline de deploy | Actions fixadas por SHA; `permissions` mínimas; usuário de deploy com um único comando permitido |
| Privacidade (LGPD) | Nenhuma pergunta ou resposta em log; IP só como hash com chave diária |

## Riscos que continuam

Nenhum controle fecha tudo. O que continua existindo, de propósito ou por limitação:

1. **A guarda do Jev é probabilística.** Uma injeção que passe por todas as camadas ainda pode levar o modelo a responder algo fora do tema. A resposta tem no máximo 300 tokens e a guarda de saída barra links e HTML, mas o risco não chega a zero.
2. **Dependência de terceiros:** OpenRouter, Jev, Cloudflare e GitHub. Se um deles cair, o chat cai ou piora. O MCP e o snapshot continuam funcionando sem o OpenRouter e sem o Jev.
3. **Uma VPS só.** Não há alta disponibilidade, e para um portfólio isso é aceitável.
4. **Código de estudo lido como habilidade.** O campo `kind` (`estudo`, `cliente`...) e a nota de cada repositório dão o contexto, mas a interpretação final é do modelo.

## Resposta a incidente

| Situação | Ação |
|---|---|
| Gasto disparou | O disjuntor abre sozinho; bloquear `/v1/chat` com uma regra da Cloudflare (efeito imediato) ou subir o `ai-api` com `JAVAI_CHAT_ENABLED=false`; baixar o limite da chave no OpenRouter |
| A IA respondeu algo indevido | Desligar o chat; reproduzir o caso; transformar em caso da bateria; corrigir; rodar a bateria; religar |
| Conteúdo indevido no índice | Rollback no CRUD com `POST /admin/v1/versions/{id}/activate`: a IA lê o snapshot anterior em até 5 minutos. Se veio de um repositório, desligar o repositório no CRUD e publicar |
| Chave vazou | Revogar na origem (OpenRouter, Jev, GitHub, Cloudflare); trocar no `.env`; reiniciar; revisar a auditoria |
| Ataque volumétrico | Modo "Under Attack" da Cloudflare; apertar a regra de rate limit |

## Decisões em aberto

1. **Teste de carga:** k6 ou Gatling? A proposta é Gatling, que é JVM.
2. **Pentest externo:** pedir ao amigo, ou a outra pessoa, para atacar o chat antes do lançamento e transformar cada achado em caso da bateria?
