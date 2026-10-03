package br.com.gilvanborges.javai.admin.migrations;

/** Os papéis do banco do CRUD (spec 02). O banco da IA tem os seus, e um não conhece os do outro. */
public final class Roles {

    /** Dono de todos os objetos. Só o contêiner admin-migrate usa, para rodar o Flyway. */
    public static final String OWNER = "admin_owner";

    /** admin-api: CRUD, publicação, auditoria. */
    public static final String APP = "admin_app";

    private Roles() {
    }
}
