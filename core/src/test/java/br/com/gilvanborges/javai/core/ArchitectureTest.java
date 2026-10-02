package br.com.gilvanborges.javai.core;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** O core é o centro da arquitetura hexagonal: só conhece o JDK e a si mesmo (spec 01). */
@AnalyzeClasses(packages = "br.com.gilvanborges.javai.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule core_so_depende_do_jdk = classes()
            .that().resideInAPackage("br.com.gilvanborges.javai.core..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "br.com.gilvanborges.javai.core..");

    @ArchTest
    static final ArchRule core_nao_acessa_banco = noClasses()
            .that().resideInAPackage("br.com.gilvanborges.javai.core..")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..");
}
