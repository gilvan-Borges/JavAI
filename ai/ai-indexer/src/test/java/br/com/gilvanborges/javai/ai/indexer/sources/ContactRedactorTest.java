package br.com.gilvanborges.javai.ai.indexer.sources;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContactRedactorTest {

    @Test
    void tira_email_e_telefone_do_cabecalho_do_curriculo() {
        String header = "GILVAN BORGES\n gilvan@exemplo.com | (21) 97591-8256 | linkedin.com/in/gilvanborges | Rio de Janeiro, RJ";

        String out = ContactRedactor.redact(header);

        assertThat(out).doesNotContain("@").doesNotContain("97591").doesNotContain("8256");
        assertThat(out).contains("GILVAN BORGES").contains("linkedin.com/in/gilvanborges").contains("Rio de Janeiro");
    }

    @Test
    void outros_formatos_de_telefone() {
        assertThat(ContactRedactor.redact("Fale: +55 21 97591-8256.")).doesNotContain("97591");
        assertThat(ContactRedactor.redact("Fale: 21975918256")).contains("21975918256"); // sem separador não é arriscado: não casa
        assertThat(ContactRedactor.redact("Tel 2591-8256")).doesNotContain("8256");
    }

    @Test
    void periodos_anos_e_numeros_de_versao_ficam() {
        String text = "Vibetex jan/2025 – atual. Java 21, Spring Boot 4.1.1, 2022 - 2024, 1200 usuários, CEP 20000-000.";

        assertThat(ContactRedactor.redact(text)).isEqualTo(text);
    }
}
