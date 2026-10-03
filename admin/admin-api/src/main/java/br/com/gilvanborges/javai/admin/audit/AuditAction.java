package br.com.gilvanborges.javai.admin.audit;

/** As ações registradas em {@code ops.audit_log}. Os valores batem com o CHECK da tabela. */
public enum AuditAction {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    STATUS("status"),
    PUBLISH("publish"),
    ACTIVATE("activate"),
    REPO("repo");

    private final String db;

    AuditAction(String db) {
        this.db = db;
    }

    public String db() {
        return db;
    }
}
