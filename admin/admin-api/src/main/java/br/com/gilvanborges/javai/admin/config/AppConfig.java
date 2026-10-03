package br.com.gilvanborges.javai.admin.config;

import br.com.gilvanborges.javai.admin.domain.publish.PortfolioNotifier;
import br.com.gilvanborges.javai.admin.domain.publish.PublicationStore;
import br.com.gilvanborges.javai.admin.domain.publish.PublishContent;
import br.com.gilvanborges.javai.admin.domain.publish.PublishedContentReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Liga o caso de uso do domínio (Java puro) às implementações do admin-api. */
@Configuration
class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PublishContent publishContent(
            PublishedContentReader reader, PublicationStore store, PortfolioNotifier notifier, Clock clock) {
        return new PublishContent(reader, store, notifier, clock);
    }

    @Bean(destroyMethod = "close")
    ExecutorService publishExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
