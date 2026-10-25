package com.omiyage.backend

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/** Base for tests that need a real Postgres (Flyway migrations, JSON/UUID/CHECK semantics). One
 *  container is started per JVM and shared across all subclasses for speed. */
abstract class PostgresIntegrationTest {

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("omiyage_test")
            .withUsername("omiyage")
            .withPassword("omiyage")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("omiyage.meishi.jwt-secret") { "integration-test-secret" }
        }
    }
}
