package br.com.gilvanborges.javai.ai.api.chat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityIntentTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "o que pode me falar do ultimo comit que ele fez?",
            "Qual foi o último commit do Gilvan?",
            "qual o ultimo commit dele",
            "quando foi o ultimo comite dele nesse projeto?",
            "Ele commitou hoje?",
            "ele commitou alguma coisa recentemente",
            "Qual a atividade recente dele no GitHub?",
            "o que ele fez recentemente?",
            "O que ele está fazendo agora?",
            "em que projeto ele trabalhou esta semana",
            "qual o projeto mais recente dele?",
            "últimos commits do controlei",
            "What was his latest commit?",
            "what is his most recent commit",
            "What has he been working on lately?",
            "Did he commit anything today?",
            "show his recent activity",
            "which is his most recent project"})
    void reconhece_perguntas_de_atividade_recente(String question) {
        assertThat(ActivityIntent.matches(question)).as(question).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Onde o Gilvan trabalha hoje?",
            "Qual é o último emprego dele?",
            "Qual foi o primeiro projeto dele?",
            "Ele tem experiência com Kafka?",
            "Qual a formação dele?",
            "Quais tecnologias ele usa?",
            "Como o JavAI se protege de prompt injection?",
            "Explique a arquitetura do Controlei",
            "Where does he work?",
            "Does he know Kubernetes?",
            "What is his education?",
            "Qual o cargo dele na Vibetex?",
            ""})
    void nao_confunde_perguntas_comuns(String question) {
        assertThat(ActivityIntent.matches(question)).as(question).isFalse();
    }

    @org.junit.jupiter.api.Test
    void tolera_nulo_e_branco() {
        assertThat(ActivityIntent.matches(null)).isFalse();
        assertThat(ActivityIntent.matches("   ")).isFalse();
    }
}
