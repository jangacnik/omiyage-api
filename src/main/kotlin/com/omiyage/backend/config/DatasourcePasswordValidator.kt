package com.omiyage.backend.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Resolving the placeholder here makes a missing DB_PASSWORD fail startup with a clear message
 *  instead of a database authentication error later; a blank password is rejected too. */
@Component
class DatasourcePasswordValidator(@Value("\${spring.datasource.password}") password: String) {

    init {
        check(password.isNotBlank()) { "DB_PASSWORD must not be blank" }
    }
}
