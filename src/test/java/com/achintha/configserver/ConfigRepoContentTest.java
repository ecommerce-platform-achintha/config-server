package com.achintha.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Checks the files under config-repo/ directly: each parses, uses an allowed port, and contains no secret-looking
 * literal. The only literal secrets allowed are the marked {@code LOCAL-DEV-ONLY} placeholders in *-local.yml files
 * and the documented local docker-compose database passwords.
 */
class ConfigRepoContentTest {

    private static final Path CONFIG_REPO = Path.of("config-repo");

    private static final Set<String> KNOWN_APPLICATIONS =
            Set.of("application", "api-gateway", "user-service", "store-service", "product-service", "order-service");

    private static final Map<String, Integer> PORTS = Map.of(
            "api-gateway", 8080, "user-service", 8081, "product-service", 8082,
            "order-service", 8083, "store-service", 8084);

    /** Last key segment that names a secret (public-key, key-id, max-failed-logins etc. are not secrets). */
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i)(secret|password|passwd|private-key|api-key|encryption-key|token)$");

    /** ${ENV}, ${ENV:} or ${ENV:${local-dev.x}}: mandatory env var, optionally with the local-only placeholder. */
    private static final Pattern ENV_WITHOUT_LITERAL_DEFAULT =
            Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*(:(\\$\\{local-dev\\.[a-z0-9-]+})?)?}");

    /** ${ENV:file:...}: a key file location, allowed in local profile files only. */
    private static final Pattern ENV_WITH_FILE_DEFAULT = Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*:file:[^}]+}");

    private static final String DEV_PLACEHOLDER_PREFIX = "LOCAL-DEV-ONLY";

    /** Local docker-compose DB credentials, same pattern as every service's own application.yml. */
    private static final Set<String> ALLOWED_DEV_DB_PASSWORDS = Set.of("${DB_PASSWORD:storeservice}");

    private static final List<Pattern> SECRET_LOOKING_VALUES = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*(PRIVATE KEY|CERTIFICATE)-----"),
            // JWT
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            // Long base64/hex blob (keys, tokens)
            Pattern.compile("(?<![A-Za-z0-9+/=_-])[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9+/=_-])"),
            // Cloud provider access keys
            Pattern.compile("AKIA[0-9A-Z]{16}"));

    @Test
    void everyFileParsesAndIsForAKnownService() throws IOException {
        List<Path> files = configFiles();
        assertThat(files).extracting(ConfigRepoContentTest::fileName).contains(
                "application.yml", "application-local.yml", "api-gateway.yml", "user-service.yml",
                "user-service-local.yml", "store-service.yml", "product-service.yml", "order-service.yml");

        for (Path file : files) {
            assertThat(KNOWN_APPLICATIONS).as("application of %s", file).contains(application(file));
            assertThat(flatten(file)).as("%s is not empty", file).isNotEmpty();
        }
    }

    @Test
    void portsMatchThePlatformPortMap() throws IOException {
        for (Path file : configFiles()) {
            Object port = flatten(file).get("server.port");
            if (port != null) {
                assertThat(port).as("server.port in %s", file).isEqualTo(PORTS.get(application(file)));
            }
        }
    }

    @Test
    void noFileContainsASecretLookingLiteral() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : configFiles()) {
            problems.addAll(secretProblems(fileName(file), flatten(file)));
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void checkerFlagsSecretLiterals() {
        List<String> problems = secretProblems("order-service.yml", Map.of(
                "security.internal.client-secret", "${SERVICE_CLIENT_SECRET_ORDER:hunter2}",
                "spring.datasource.password", "orderservice",
                "security.jwt.signing.private-key", "-----BEGIN PRIVATE KEY-----MIIEvQ...",
                "some.harmless.name", "dGhpcyBpcyBhIGxvbmcgYmFzZTY0IGVuY29kZWQgc2VjcmV0IGtleQ==",
                "local-dev.service-client-secret", "LOCAL-DEV-ONLY-x",
                "security.jwt.signing.public-key", "${JWT_PUBLIC_KEY:file:./keys/jwt-public.pem}"));
        for (String flagged : List.of("security.internal.client-secret ", "spring.datasource.password ",
                "security.jwt.signing.private-key ", "some.harmless.name ", "local-dev.service-client-secret ")) {
            assertThat(problems).as(flagged).anyMatch(problem -> problem.contains(": " + flagged));
        }
        assertThat(problems).noneMatch(problem -> problem.contains("public-key"));

        assertThat(secretProblems("order-service-local.yml", Map.of(
                "local-dev.service-client-secret", "LOCAL-DEV-ONLY-x",
                "security.jwt.signing.private-key", "${JWT_PRIVATE_KEY:file:./keys/jwt-private.pem}",
                "security.internal.client-secret", "${SERVICE_CLIENT_SECRET_ORDER:${local-dev.service-client-secret}}",
                "bootstrap.super-admin.initial-password", "${SUPER_ADMIN_INITIAL_PASSWORD:}")))
                .isEmpty();
    }

    static List<String> secretProblems(String fileName, Map<String, Object> properties) {
        boolean localFile = fileName.endsWith("-local.yml");
        List<String> problems = new ArrayList<>();
        properties.forEach((key, rawValue) -> {
            String value = String.valueOf(rawValue);
            String where = fileName + ": " + key;

            boolean markedPlaceholder = value.startsWith(DEV_PLACEHOLDER_PREFIX);
            if (markedPlaceholder && !localFile) {
                problems.add(where + " has the local-dev placeholder outside a *-local.yml file");
            }
            if (markedPlaceholder && localFile) {
                return;
            }
            for (Pattern pattern : SECRET_LOOKING_VALUES) {
                if (pattern.matcher(value).find()) {
                    problems.add(where + " looks like a secret (" + pattern + ")");
                }
            }
            if (isSensitive(key) && !isAllowedSensitiveValue(value, localFile)) {
                problems.add(where + " must come from an env var without a literal default");
            }
        });
        return problems;
    }

    private static boolean isSensitive(String key) {
        String lastSegment = key.substring(key.lastIndexOf('.') + 1).replaceAll("\\[\\d+]$", "");
        return SENSITIVE_KEY.matcher(lastSegment).find();
    }

    private static boolean isAllowedSensitiveValue(String value, boolean localFile) {
        return ENV_WITHOUT_LITERAL_DEFAULT.matcher(value).matches()
                || ALLOWED_DEV_DB_PASSWORDS.contains(value)
                || (localFile && value.startsWith(DEV_PLACEHOLDER_PREFIX))
                || (localFile && ENV_WITH_FILE_DEFAULT.matcher(value).matches());
    }

    private static List<Path> configFiles() throws IOException {
        try (Stream<Path> files = Files.list(CONFIG_REPO)) {
            return files.filter(file -> fileName(file).matches(".*\\.ya?ml")).sorted().toList();
        }
    }

    private static String fileName(Path file) {
        return file.getFileName().toString();
    }

    /** "user-service-local.yml" -> "user-service"; "application.yml" -> "application". */
    private static String application(Path file) {
        String base = fileName(file).replaceFirst("\\.ya?ml$", "");
        return KNOWN_APPLICATIONS.stream()
                .filter(name -> base.equals(name) || base.startsWith(name + "-"))
                .max((a, b) -> Integer.compare(a.length(), b.length()))
                .orElse(base);
    }

    private static Map<String, Object> flatten(Path file) throws IOException {
        Map<String, Object> flat = new LinkedHashMap<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            for (Object document : new Yaml().loadAll(reader)) {
                flatten("", document, flat);
            }
        }
        return flat;
    }

    private static void flatten(String prefix, Object node, Map<String, Object> flat) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> flatten(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, value, flat));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), flat);
            }
        } else if (node != null) {
            flat.put(prefix, node);
        }
    }
}
