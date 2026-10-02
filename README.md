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
curl http://localhost:8888/actuator/health          # {"status":"UP"}
curl http://localhost:8888/user-service/default     # config for user-service
curl http://localhost:8888/user-service/local       # ... with the local profile overrides
curl http://localhost:8888/user-service-default.yml
```

## What is in `config-repo/`

The platform contract is in [`docs/marketplace-design.md`](docs/marketplace-design.md).

| File                     | Served to                     | Contents                                                                                 |
|--------------------------|-------------------------------|------------------------------------------------------------------------------------------|
| `application.yml`        | every service                 | JWT issuer/audience/JWKS URI/clock skew, CORS origins, internal client id, Kafka topic names, `app.timezone`, actuator lockdown |
| `application-local.yml`  | every service, `local` profile | the marked local-dev client-secret placeholder, extra actuator endpoints for debugging   |
| `api-gateway.yml`        | api-gateway                   | JWKS/issuer/audience (from shared), public-route list, per-client rate-limit tiers, CORS details |
| `user-service.yml`       | user-service (8081)           | RS256 signing keys + `kid`, internal client registry, token TTLs, lockout, ban grace, super-admin bootstrap, ShedLock, schedulers |
| `user-service-local.yml` | user-service, `local` profile | signing keys read from the git-ignored `keys/` folder                                     |
| `store-service.yml`      | store-service (8084)          | port, datasource (5435/`storedb`), Flyway + `ddl-auto=validate`, Kafka, Eureka, page size 50, client secret, ShedLock |
| `product-service.yml`    | product-service (8082)        | client secret, ShedLock, reservation clean-up scheduler, outbox relay                    |
| `order-service.yml`      | order-service (8083)          | client secret, order-events topic, ShedLock, deadline scheduler, outbox relay            |

Precedence, highest first: `{service}-{profile}.yml` > `application-{profile}.yml` > `{service}.yml` >
`application.yml`. Values from the Config Server also override a service's own `application.yml`.

Every `${NAME:default}` placeholder is resolved **by the client service** from its own environment, so set the
variables on the service, not on the Config Server. Any property can also be overridden with Spring's relaxed
binding (e.g. `AUTH_ACCESSTOKENMINUTES=5` for `auth.access-token-minutes`).

### Secrets policy

- No real secret, key or password is committed. Secrets come from environment variables (or a secret store
  that sets them).
- The only literal secret allowed is the marked placeholder `LOCAL-DEV-ONLY-...` in `application-local.yml`.
  Service files use it as a fallback (`${SERVICE_CLIENT_SECRET_ORDER:${local-dev.service-client-secret}}`), and
  it is served only to the `local` profile. In any other profile the property cannot be resolved without the env
  var. **Each service must refuse to start** outside `local` if a secret is blank, still contains `${`, or starts
  with `LOCAL-DEV-ONLY`. That check lives in each service (user, store, product, order).
- The local docker-compose DB password defaults (`storeservice` etc.) follow the existing per-service pattern;
  set `DB_PASSWORD` everywhere else.
- `ConfigRepoContentTest` fails the build if a file contains a secret-looking literal (PEM block, JWT, long
  base64/hex blob, cloud access key, or a `*secret`/`*password`/`*private-key` property with a literal default).
- `keys/`, `*.pem`, `*.key`, `*.p12`, `*.jks` and `.env` are git-ignored and docker-ignored. The native backend
  serves **any** file under `config-repo/` over HTTP, so never put a key there.

### Actuator

- Config Server: only `health` and `info`, in every profile; `env`/`configprops` values are masked even if
  someone exposes them later.
- Services: `application.yml` limits exposure to `health,info`; the `local` profile adds `env`, `configprops`,
  `metrics`, `circuitbreakers`, `circuitbreakerevents` and `gateway` (values still masked).
- `GET /actuator/env` on the Config Server is *not* the actuator: it is the Config Server API for an application
  named `actuator` with profile `env`. The Config Server API (`/{application}/{profile}`) is unauthenticated and
  returns every placeholder in `config-repo/`, so keep port 8888 on the internal network only.

## Local RSA keys (user-service)

user-service signs access tokens with RS256. Generate a dev key pair once, **in the user-service project root**:

```bash
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/jwt-private.pem
openssl pkey -in keys/jwt-private.pem -pubout -out keys/jwt-public.pem
echo "keys/" >> .gitignore    # and .dockerignore; never commit keys, not even dev ones
```

Run user-service with the `local` profile (`SPRING_PROFILES_ACTIVE=local`) and it reads
`file:./keys/jwt-private.pem` / `file:./keys/jwt-public.pem` (see `user-service-local.yml`).

Outside `local`, set `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY` to either the PEM text or a resource location such
as `file:/run/secrets/jwt-private.pem`. To pass the PEM text from a file:

```bash
export JWT_PRIVATE_KEY="$(cat keys/jwt-private.pem)"
export JWT_PUBLIC_KEY="$(cat keys/jwt-public.pem)"
```

Change `JWT_KEY_ID` whenever you rotate the key pair. Other services never need the keys: they fetch the
public key from `JWKS_URI`.

## Environment variables

All are read by the client service named in the "Needed by" column.

| Variable                         | Needed by                         | Default (local)                                  | Notes |
|----------------------------------|-----------------------------------|--------------------------------------------------|-------|
| `CONFIG_REPO_LOCATION`           | config-server                     | `file:./config-repo/`                            | |
| `JWKS_URI`                       | all services, api-gateway         | `http://localhost:8081/.well-known/jwks.json`    | |
| `JWT_ISSUER`                     | all services, api-gateway         | `user-service`                                   | |
| `JWT_AUDIENCE`                   | all services, api-gateway         | `marketplace`                                    | |
| `JWT_CLOCK_SKEW_SECONDS`         | all services, api-gateway         | `30`                                             | |
| `CORS_ALLOWED_ORIGINS`           | api-gateway                       | `http://localhost:3000,http://localhost:5173`    | comma-separated exact origins |
| `GATEWAY_TRUSTED_PROXIES`        | api-gateway                       | empty (trust no `X-Forwarded-For`)               | |
| `KAFKA_TOPIC_USER_EVENTS`        | user, store, product, order       | `user-events`                                    | |
| `KAFKA_TOPIC_STORE_EVENTS`       | store, product                    | `store-events`                                   | |
| `KAFKA_TOPIC_PRODUCT_EVENTS`     | product                           | `product-events`                                 | |
| `KAFKA_TOPIC_ORDER_EVENTS`       | order, store, product             | `order-events`                                   | |
| `APP_TIMEZONE`                   | all services                      | `Asia/Colombo`                                   | |
| `JWT_PRIVATE_KEY`                | user-service                      | none; `local`: `file:./keys/jwt-private.pem`     | **secret**, PEM or resource location |
| `JWT_PUBLIC_KEY`                 | user-service                      | none; `local`: `file:./keys/jwt-public.pem`      | PEM or resource location |
| `JWT_KEY_ID`                     | user-service                      | `user-service-rs256-1` (`local`: `user-service-local-dev`) | |
| `SUPER_ADMIN_EMAIL`              | user-service                      | none                                             | required on an empty database |
| `SUPER_ADMIN_INITIAL_PASSWORD`   | user-service                      | none                                             | **secret**, required on an empty database |
| `SERVICE_CLIENT_SECRET_STORE`    | store-service **and** user-service | none; `local`: marked placeholder               | **secret**, same value in both |
| `SERVICE_CLIENT_SECRET_PRODUCT`  | product-service **and** user-service | none; `local`: marked placeholder             | **secret**, same value in both |
| `SERVICE_CLIENT_SECRET_ORDER`    | order-service **and** user-service | none; `local`: marked placeholder               | **secret**, same value in both |
| `SHEDLOCK_ENABLED`               | user, store, product, order       | `true`                                           | |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | store-service                    | `localhost` / `5435` / `storedb`                 | |
| `DB_USERNAME` / `DB_PASSWORD`    | store-service                     | `storeservice` / `storeservice`                  | **secret** outside local |
| `KAFKA_BOOTSTRAP_SERVERS`        | store-service                     | `localhost:9092`                                 | |
| `EUREKA_URL`                     | store-service                     | `http://localhost:8761/eureka/`                  | |
| `SPRING_PROFILES_ACTIVE`         | any service                       | none                                             | `local` on developer machines |

The other services' DB/Kafka/Eureka variables are unchanged and stay in their own READMEs.

## Adding service configs

Add files to `config-repo/` using the `{application}[-{profile}].yml` naming pattern:

| File                     | Served to                                        |
|--------------------------|--------------------------------------------------|
| `application.yml`        | every service (shared defaults)                  |
| `user-service.yml`       | `spring.application.name=user-service`           |
| `user-service-local.yml` | `user-service` with the `local` profile active   |

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

Service ports: api-gateway 8080, user-service 8081, product-service 8082, order-service 8083,
store-service 8084, service-registry 8761, config-server 8888. `ConfigRepoContentTest` checks that every file
parses, that `server.port` matches this list, and the secrets policy above.
