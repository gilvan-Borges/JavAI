# Contribuindo com o JavAI

Obrigado pelo interesse! Este projeto é também material de estudo: as regras abaixo existem para ele continuar legível e defensável de ponta a ponta.

## Antes de escrever código

1. **Leia as [specs](specs/README.md).** O projeto segue Spec-Driven Development: mudança de comportamento ou de direção entra primeiro numa spec (ou numa alteração de spec) e só depois vira código.
2. **A régua técnica é a [spec 06](specs/06-base-academica.md)** (as duas ementas de pós que guiam o projeto). Uma proposta que contraria um tema dela precisa registrar o porquê na própria spec, na tabela de decisões.
3. Procure uma issue aberta ou abra uma descrevendo o problema antes do PR.

## Regras que não se negocia

- **Isolamento CRUD × IA:** nada de código, banco ou rede de dados compartilhados entre `admin/` e `ai/`. O ArchUnit e o Maven Enforcer falham o build se isso for violado.
- **RAG literal:** trecho de índice é texto literal da fonte, com link. Nenhuma IA reescreve conteúdo que vira índice.
- **Nenhum segredo no código ou no Git:** valor sensível vem do ambiente (`infra/.env.example` documenta todos). Dados pessoais de visitantes não são gravados; e-mail e telefone são removidos das fontes antes de indexar.
- **Migração de banco é imutável:** nunca edite uma migração aplicada; crie a próxima (`V<n>__*.sql`).
- **Erro nunca vaza detalhe:** mensagens de exceção e Problem Details não carregam stack trace, SQL nem corpo de resposta de terceiros.

## Qualidade

- Teste acompanha o código: JUnit 5 + AssertJ; Testcontainers para banco; ArchUnit para fronteiras. `mvn verify` precisa passar — ele inclui a análise estática (ErrorProne).
- Comentário explica o porquê, não o quê, no idioma do arquivo (pt-BR).
- Commits pequenos, mensagem no padrão `tipo(escopo): resumo` (como no histórico).

## Para usar com os seus dados

O conteúdo da IA é todo configurável: cadastre o seu no CRUD e aponte as fontes públicas (`INDEXER_SOURCES_*`) para o seu site, currículo e GitHub. Nenhum dado do autor original fica embutido no código — os padrões de produção que aparecem em `application.yml` são sobrescritos por ambiente.

## Deploy

Só pelo CI/CD. Não há deploy manual: se o fluxo do GitHub Actions não cobre o seu caso, a mudança é no fluxo.
