package br.com.gilvanborges.javai.ai.api;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * As regras que mantêm o MCP só de leitura (spec 01 e 04). Se alguém violar uma, o build quebra.
 */
@AnalyzeClasses(packages = "br.com.gilvanborges.javai.ai.api", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule ferramentas_e_prompts_nao_tocam_no_banco_nem_na_rede = noClasses()
            .that().resideInAPackage("..api.mcp..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "java.sql..", "javax.sql..", "org.springframework.jdbc..", "java.net..", "java.nio.file..",
                    "org.eclipse.jgit..")
            .because("as ferramentas só leem o índice em memória: nada de SQL, rede nem arquivos (spec 04)");

    @ArchTest
    static final ArchRule so_as_classes_jdbc_falam_com_o_banco = noClasses()
            .that().haveSimpleNameNotStartingWith("Jdbc")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "org.springframework.jdbc..")
            .because("o acesso ao banco fica nas classes Jdbc*, com SQL constante e parâmetros nomeados");

    @ArchTest
    static final ArchRule nao_depende_do_crud_nem_do_indexador = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "br.com.gilvanborges.javai.admin..", "br.com.gilvanborges.javai.ai.indexer..")
            .because("a API não conhece o CRUD, e o indexador é outro processo (spec 01, isolamento)");

    @ArchTest
    static final ArchRule o_chat_so_chama_a_rede_pelos_clientes_externos = noClasses()
            .that().resideInAPackage("..api.chat..")
            .and().haveSimpleNameNotEndingWith("Classifier").and().haveSimpleNameNotStartingWith("OpenRouter")
            .and().haveSimpleNameNotStartingWith("Turnstile").and().doNotHaveSimpleName("ChatConfig")
            .should().dependOnClassesThat().resideInAnyPackage("java.net.http..")
            .because("só o Jev, o OpenRouter e o Turnstile falam com a internet, e cada um tem a sua classe");
}
