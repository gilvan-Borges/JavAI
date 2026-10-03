package br.com.gilvanborges.javai.ai.core.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTest {

    @Test
    void normaliza_acentos_caixa_e_compatibilidade() {
        assertThat(TextNormalizer.normalize("Experiência EM Arquitetura Hexagonal")).isEqualTo("experiencia em arquitetura hexagonal");
        assertThat(TextNormalizer.normalize("ﬁnanceiro")).isEqualTo("financeiro"); // ligadura fi (NFKC)
        assertThat(TextNormalizer.normalize(null)).isEmpty();
    }

    @Test
    void tokeniza_sem_palavras_comuns_e_sem_acento() {
        assertThat(Tokenizer.tokens("Onde o Gilvan trabalha hoje?")).containsExactly("gilvan", "trabalha", "hoje");
        assertThat(Tokenizer.tokens("What does he use for messaging?")).containsExactly("use", "messaging");
    }

    @Test
    void mantem_termos_tecnicos_inteiros() {
        assertThat(Tokenizer.tokens("Java 21, Spring Boot e C# no .NET")).contains("java", "21", "spring", "boot", "c#", "net");
    }

    @Test
    void acento_na_pergunta_casa_com_texto_sem_acento() {
        assertThat(Tokenizer.tokens("mensageria assíncrona")).isEqualTo(Tokenizer.tokens("mensageria assincrona"));
    }
}
