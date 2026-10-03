package br.com.gilvanborges.javai.ai.api.admin;

import br.com.gilvanborges.javai.ai.api.chat.Keys;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class AdminConfig {

    @Bean
    AdminSession adminSession(Keys keys, AdminProperties props, Clock clock) {
        return new AdminSession(keys, props, clock);
    }

    @Bean
    LoginThrottle loginThrottle(AdminProperties props, Clock clock) {
        return new LoginThrottle(clock, props.maxFailures(), Duration.ofMinutes(props.lockMinutes()));
    }

    @Bean
    FilterRegistrationBean<AdminFilter> adminGuard(AdminProperties props, AdminSession session) {
        var registration = new FilterRegistrationBean<>(new AdminFilter(props, session));
        registration.addUrlPatterns("/admin", "/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
