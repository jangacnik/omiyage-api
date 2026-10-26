package com.omiyage.backend.config

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer

class DatasourcePasswordValidatorTest {

    @Test
    fun `rejects a blank password`() {
        assertThrows<IllegalStateException> { DatasourcePasswordValidator("  ") }
    }

    @Test
    fun `accepts a non-blank password`() {
        assertDoesNotThrow { DatasourcePasswordValidator("secret") }
    }

    private val runner = ApplicationContextRunner().withUserConfiguration(
        PropertySourcesPlaceholderConfigurer::class.java,
        DatasourcePasswordValidator::class.java,
    )

    @Test
    fun `startup fails when the datasource password is not configured`() {
        runner.run { assertNotNull(it.startupFailure) }
    }

    @Test
    fun `startup succeeds when the datasource password is configured`() {
        runner.withPropertyValues("spring.datasource.password=secret").run { assertNull(it.startupFailure) }
    }
}
