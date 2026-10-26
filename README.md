# Omiyage API

![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7f52ff?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6db33f?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Flyway-4169e1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/deploy-Docker%20%7C%20Kubernetes-2496ed?logo=docker&logoColor=white)

Backend for **Omiyage** (お土産, "souvenir"), a friends-and-family shop: browse an inventory of stuff, request things to be bought in Japan, and have them shipped over. Think of it as a very small, very personal import service where the "customer support" is a friend.

## Purpose

- Serve the inventory, orders, custom item requests and shipments to the [omiyage-frontend](../omiyage-frontend) SPA
- Give admins the tools to manage inventory, orders, shipping rates and exchange rates
- Delegate accounts and login to **MeishiApi**, so there is a single source of truth for users

See [ARCHITECTURE.md](ARCHITECTURE.md) for the system diagram and auth flow.

## Stack

| Concern | Choice |
| --- | --- |
| Language | Kotlin 2.4 on Java 21 |
| Framework | Spring Boot 4.1 (Web, Security, Validation, Data JPA) |
| Database | PostgreSQL with Flyway migrations |
| Auth | HS256 JWTs issued by MeishiApi, verified with a shared secret |
| Observability | Spring Actuator + Micrometer Prometheus |
| API docs | springdoc-openapi |
| Tests | JUnit 5, MockK, Testcontainers, JaCoCo coverage |
| Delivery | Docker image, Kubernetes manifests in `deploy/` |

## Getting started

Requires JDK 21 and Docker (for PostgreSQL and the integration tests).

```sh
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The JWT secret must match the one configured in MeishiApi.

## Testing

```sh
./gradlew test
```

Integration tests use Testcontainers and need a Docker socket. When the `CI` environment variable is set they are skipped. A JaCoCo report is written after every test run.

## Design notes

- **Login is proxied.** The browser talks to one API origin; this service forwards credentials to MeishiApi and checks the account is not frozen, so MeishiApi needs no CORS setup for a second frontend.
- **Stateless auth.** No sessions; every request carries a JWT.
- **Migrations over auto-DDL.** Schema changes are versioned Flyway scripts.
