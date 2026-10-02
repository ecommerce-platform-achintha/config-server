package com.achintha.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTests {

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Value("${local.server.port}")
    private int port;

    @Test
    void servesUserServiceConfigFromConfigRepo() {
        assertThat(firstValue(environmentRepository.findOne("user-service", "default", null), "server.port"))
                .contains(8081);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api-gateway", "user-service", "store-service", "product-service", "order-service"})
    void everyServiceFileLoadsTogetherWithSharedDefaults(String service) {
        Environment environment = environmentRepository.findOne(service, "default", null);

        assertThat(sourceNames(environment))
                .anyMatch(name -> name.contains("config-repo/" + service + ".yml"))
                .anyMatch(name -> name.contains("config-repo/application.yml"));
        assertThat(firstValue(environment, "security.jwt.jwks-uri"))
                .contains("${JWKS_URI:http://localhost:8081/.well-known/jwks.json}");
        assertThat(firstValue(environment, "app.kafka.topics.order-events"))
                .contains("${KAFKA_TOPIC_ORDER_EVENTS:order-events}");
    }

    @ParameterizedTest
    @CsvSource({"user-service, 8081", "store-service, 8084"})
    void servicePortsAreUnchanged(String service, int port) {
        assertThat(firstValue(environmentRepository.findOne(service, "default", null), "server.port"))
                .contains(port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user-service", "store-service", "product-service", "order-service"})
    void localDevPlaceholderIsOnlyServedToTheLocalProfile(String service) {
        // Outside "local", nothing defines local-dev.*, so ${SERVICE_CLIENT_SECRET_X:${local-dev...}} cannot
        // resolve unless the env var is set.
        assertThat(firstValue(environmentRepository.findOne(service, "default", null),
                "local-dev.service-client-secret")).isEmpty();
        assertThat(firstValue(environmentRepository.findOne(service, "prod", null),
                "local-dev.service-client-secret")).isEmpty();
        assertThat(firstValue(environmentRepository.findOne(service, "local", null),
                "local-dev.service-client-secret")).hasValueSatisfying(
                        value -> assertThat(value.toString()).startsWith("LOCAL-DEV-ONLY"));
    }

    @Test
    void userServiceSigningKeysHaveNoDefaultOutsideLocal() {
        Environment defaults = environmentRepository.findOne("user-service", "default", null);
        assertThat(firstValue(defaults, "security.jwt.signing.private-key")).contains("${JWT_PRIVATE_KEY}");
        assertThat(firstValue(defaults, "security.jwt.secret")).isEmpty();

        Environment local = environmentRepository.findOne("user-service", "local", null);
        assertThat(firstValue(local, "security.jwt.signing.private-key"))
                .contains("${JWT_PRIVATE_KEY:file:./keys/jwt-private.pem}");
    }

    @Test
    void actuatorExposesOnlyHealthAndInfo() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/info").statusCode()).isEqualTo(200);
        assertThat(get("/actuator").body())
                .contains("\"health\"", "\"info\"")
                .doesNotContain("\"env\"", "\"configprops\"", "\"beans\"", "\"heapdump\"", "\"refresh\"", "\"loggers\"");

        // Two-segment paths are the Config Server's own /{application}/{profile} API (here: app "actuator",
        // profile "env"), which serves config-repo placeholders, never this server's environment.
        assertThat(get("/actuator/env").body())
                .contains("\"name\":\"actuator\"")
                .doesNotContain("systemEnvironment", "systemProperties");
    }

    @Test
    void sharedDefaultsRestrictClientActuatorsOutsideLocal() {
        assertThat(firstValue(environmentRepository.findOne("order-service", "default", null),
                "management.endpoints.web.exposure.include")).contains("health,info");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static List<String> sourceNames(Environment environment) {
        return environment.getPropertySources().stream().map(PropertySource::getName).toList();
    }

    /** Value from the highest-precedence source that defines the key (sources are ordered highest first). */
    private static Optional<Object> firstValue(Environment environment, String key) {
        return environment.getPropertySources().stream()
                .map(PropertySource::getSource)
                .filter(source -> source.containsKey(key))
                .map(source -> (Object) ((Map<?, ?>) source).get(key))
                .findFirst();
    }
}
