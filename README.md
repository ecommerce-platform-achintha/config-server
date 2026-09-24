# config-server

Spring Cloud Config Server (Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3).

It serves configuration for other services from the local `config-repo/` folder
using the **native** (file-system) backend. It is not backed by git yet.

## Run locally

Prerequisites: JDK 25 and Maven 3.9+.

```bash
mvn spring-boot:run
```

Run it from the project root, because the config location `file:./config-repo/`
is resolved relative to the working directory. To use another folder:

```bash
CONFIG_REPO_LOCATION=file:/path/to/config-repo/ mvn spring-boot:run
```

Or build a jar and run it:

```bash
mvn clean package
java -jar target/config-server-0.0.1-SNAPSHOT.jar
```

## Run with Docker

```bash
docker build -t config-server .
docker run --rm -p 8888:8888 config-server
```

The image includes a copy of `config-repo/` from build time. To edit configs
without rebuilding, mount the folder instead:

```bash
docker run --rm -p 8888:8888 -v "$(pwd)/config-repo:/app/config-repo:ro" config-server
```

## Check that it works

```bash
curl http://localhost:8888/actuator/health        # {"status":"UP"}
curl http://localhost:8888/user-service/default   # config for user-service
curl http://localhost:8888/user-service-default.yml
```

## Adding service configs

Add files to `config-repo/` using the `{application}[-{profile}].yml` naming pattern:

| File                     | Served to                                        |
|--------------------------|--------------------------------------------------|
| `application.yml`        | every service (shared defaults)                  |
| `user-service.yml`       | `spring.application.name=user-service`           |
| `user-service-dev.yml`   | `user-service` with the `dev` profile active     |

When more than one file matches, the profile-specific file wins over the service
file, and the service file wins over `application.yml`.

A client service connects with:

```yaml
spring:
  application:
    name: user-service
  config:
    import: "optional:configserver:http://localhost:8888"
```

(The client needs the `spring-cloud-starter-config` dependency.)
