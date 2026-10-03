package br.com.gilvanborges.javai.ai.migrations;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/**
 * Cria e atualiza os papéis e o banco da IA, como superusuário, antes do Flyway (spec 02).
 *
 * <p>DDL não aceita parâmetros, então todo comando com nome ou senha é montado pelo próprio PostgreSQL com
 * {@code format()}: {@code %I} cita identificadores e {@code %L} cita literais. Nada é concatenado em Java.
 *
 * <p>É idempotente e reaplica as senhas em todo deploy: trocar uma senha é só mudar o {@code .env}.
 */
public final class Bootstrap {

    private static final System.Logger LOG = System.getLogger(Bootstrap.class.getName());

    private static final String ROLE_EXISTS = "SELECT 1 FROM pg_roles WHERE rolname = ?";
    private static final String CREATE_ROLE = "SELECT format('CREATE ROLE %I', ?::text)";
    private static final String CONFIGURE_ROLE = """
            SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS \
            NOREPLICATION NOINHERIT CONNECTION LIMIT %s PASSWORD %L', ?::text, ?::int, ?::text)""";
    private static final String DATABASE_EXISTS = "SELECT 1 FROM pg_database WHERE datname = ?";
    private static final String CREATE_DATABASE =
            "SELECT format('CREATE DATABASE %I OWNER %I ENCODING ''UTF8'' TEMPLATE template0', ?::text, ?::text)";
    private static final String REVOKE_DATABASE = "SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', ?::text)";
    private static final String GRANT_CONNECT =
            "SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', ?::text, ?::text, ?::text)";
    private static final String ROLE_SETTING =
            "SELECT format('ALTER ROLE %I IN DATABASE %I SET %I = %L', ?::text, ?::text, ?::text, ?::text)";

    private Bootstrap() {
    }

    public static void run(DatabaseSettings settings) throws SQLException {
        var superuser = settings.superuser();
        try (Connection c = DriverManager.getConnection(settings.maintenanceUrl(), superuser.user(), superuser.password())) {
            ensureRole(c, settings.owner(), 3);
            ensureRole(c, settings.indexer(), 5);
            ensureRole(c, settings.reader(), 20);

            if (!exists(c, DATABASE_EXISTS, settings.database())) {
                execute(c, format(c, CREATE_DATABASE, settings.database(), Roles.OWNER));
                LOG.log(System.Logger.Level.INFO, "Banco {0} criado.", settings.database());
            }
            execute(c, format(c, REVOKE_DATABASE, settings.database()));
            execute(c, format(c, GRANT_CONNECT, settings.database(), Roles.INDEXER, Roles.READER));
            // O banco de manutenção não é para os apps. O pg_hba já bloqueia; isto é a segunda trava.
            execute(c, format(c, REVOKE_DATABASE, "postgres"));

            // O indexador faz escritas grandes (vetores); a API pública não pode prender uma consulta por mais de 2 s.
            applySettings(c, settings.database(), Roles.INDEXER, Map.of(
                    "statement_timeout", "60s",
                    "idle_in_transaction_session_timeout", "30s",
                    "search_path", "pg_catalog"));
            applySettings(c, settings.database(), Roles.READER, Map.of(
                    "statement_timeout", "2s",
                    "idle_in_transaction_session_timeout", "10s",
                    "search_path", "pg_catalog"));
        }
    }

    private static void ensureRole(Connection c, Credentials role, int connectionLimit) throws SQLException {
        if (!exists(c, ROLE_EXISTS, role.user())) {
            execute(c, format(c, CREATE_ROLE, role.user()));
            LOG.log(System.Logger.Level.INFO, "Papel {0} criado.", role.user());
        }
        try (PreparedStatement ps = c.prepareStatement(CONFIGURE_ROLE)) {
            ps.setString(1, role.user());
            ps.setInt(2, connectionLimit);
            ps.setString(3, role.password());
            execute(c, single(ps));
        }
    }

    private static void applySettings(Connection c, String database, String role, Map<String, String> values)
            throws SQLException {
        for (var entry : values.entrySet()) {
            execute(c, format(c, ROLE_SETTING, role, database, entry.getKey(), entry.getValue()));
        }
    }

    private static boolean exists(Connection c, String sql, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String format(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            return single(ps);
        }
    }

    private static String single(PreparedStatement ps) throws SQLException {
        try (var rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void execute(Connection c, String statement) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(statement);
        }
    }
}
