package br.com.gilvanborges.javai.admin.migrations;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Aplica as migrações como {@code javai_owner}. O histórico do Flyway fica num schema próprio. */
public final class Migrations {

    private Migrations() {
    }

    public static MigrateResult apply(DatabaseSettings settings) {
        var owner = settings.owner();
        return Flyway.configure()
                .dataSource(settings.url(), owner.user(), owner.password())
                .locations("classpath:db/migration")
                .schemas("flyway")
                .defaultSchema("flyway")
                .createSchemas(true)
                .placeholderReplacement(false)
                .cleanDisabled(true)
                .validateMigrationNaming(true)
                .load()
                .migrate();
    }
}
