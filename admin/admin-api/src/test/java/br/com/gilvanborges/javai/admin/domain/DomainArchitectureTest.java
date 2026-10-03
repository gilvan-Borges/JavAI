package br.com.gilvanborges.javai.admin.domain;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** O domínio do CRUD é o centro da arquitetura hexagonal: só conhece o JDK e a si mesmo (spec 01). */
@AnalyzeClasses(packages = "br.com.gilvanborges.javai.admin.domain", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainArchitectureTest {

    @ArchTest
    static final ArchRule dominio_so_depende_do_jdk = classes()
            .that().resideInAPackage("br.com.gilvanborges.javai.admin.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "br.com.gilvanborges.javai.admin.domain..");

    @ArchTest
    static final ArchRule dominio_nao_acessa_banco = noClasses()
            .that().resideInAPackage("br.com.gilvanborges.javai.admin.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..");
}
