package com.omiyage.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan

@SpringBootApplication
@ConfigurationPropertiesScan
class OmiyageApplication

fun main(args: Array<String>) {
    runApplication<OmiyageApplication>(*args)
}
