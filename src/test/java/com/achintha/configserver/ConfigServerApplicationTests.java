package com.achintha.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;

@SpringBootTest
class ConfigServerApplicationTests {

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Test
    void servesUserServiceConfigFromConfigRepo() {
        Environment environment = environmentRepository.findOne("user-service", "default", null);

        assertThat(environment.getPropertySources())
                .anySatisfy(source -> assertThat(source.getSource().get("server.port")).isEqualTo(8081));
    }
}
