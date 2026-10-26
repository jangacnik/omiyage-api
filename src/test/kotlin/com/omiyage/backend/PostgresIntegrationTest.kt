package com.omiyage.backend

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/** Base for tests that need a real Postgres (Flyway migrations, JSON/UUID/CHECK semantics). One
 *  container is started per JVM and shared across all subclasses for speed. */
abstract class PostgresIntegrationTest {

    companion object {
        /** CI provides a Postgres service and sets TEST_DB_URL; locally Testcontainers starts one. */
        private val externalDbUrl: String? = System.getenv("TEST_DB_URL")

        private val postgres: PostgreSQLContainer<*> by lazy {
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("omiyage_test")
                .withUsername("omiyage")
                .withPassword("omiyage")
                .apply { start() }
        }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { externalDbUrl ?: postgres.jdbcUrl }
            registry.add("spring.datasource.username") { if (externalDbUrl != null) "omiyage" else postgres.username }
            registry.add("spring.datasource.password") { if (externalDbUrl != null) "omiyage" else postgres.password }
            registry.add("omiyage.meishi.jwt-secret") { "integration-test-secret" }
        }
    }
}
