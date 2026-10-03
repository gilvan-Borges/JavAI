package br.com.gilvanborges.javai.admin.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void o_swagger_chama_a_api_por_url_relativa_para_herdar_o_https_da_pagina() {
        var servers = new OpenApiConfig().adminOpenApi().getServers();

        assertThat(servers).singleElement().satisfies(server -> assertThat(server.getUrl()).isEqualTo("/"));
    }
}
