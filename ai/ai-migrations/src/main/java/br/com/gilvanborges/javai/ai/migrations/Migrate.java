package br.com.gilvanborges.javai.ai.migrations;

/** Ponto de entrada do contêiner ai-migrate: bootstrap dos papéis e depois o Flyway. */
public final class Migrate {

    private static final System.Logger LOG = System.getLogger(Migrate.class.getName());

    private Migrate() {
    }

    public static void main(String[] args) {
        try {
            var settings = DatabaseSettings.fromEnvironment(System.getenv());
            Bootstrap.run(settings);
            var result = Migrations.apply(settings);
            LOG.log(System.Logger.Level.INFO, "Migrações aplicadas: {0}. Versão do schema: {1}.",
                    result.migrationsExecuted, result.targetSchemaVersion);
        } catch (Exception e) {
            // Só a classe e a mensagem: nenhuma das nossas mensagens carrega senha.
            LOG.log(System.Logger.Level.ERROR, "Falha nas migrações: {0}: {1}",
                    e.getClass().getSimpleName(), e.getMessage());
            System.exit(1);
        }
    }
}
