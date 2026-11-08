package com.omiyage.backend.web

// Jackson 2 and Jackson 3 are both on the classpath (spring-boot-jackson2 next to Boot 4's Jackson 3)
// and their exception types differ, but both expose getPath() with name/index references and
// getTargetType(). Read them by name so one code path serves whichever mapper parsed the body.

/** The JSON field path of a binding error, e.g. `items[0].inventoryItemId`, or null when there is none. */
internal fun jsonFieldPath(error: Throwable): String? {
    val path = causes(error).firstNotNullOfOrNull { call(it, "getPath") as? List<*> } ?: return null
    val text = path.joinToString("") { reference ->
        val index = call(reference, "getIndex") as? Int ?: -1
        val name = (call(reference, "getPropertyName") ?: call(reference, "getFieldName")) as? String
        if (index >= 0) "[$index]" else ".$name"
    }
    return text.removePrefix(".").ifEmpty { null }
}

/** The Java type Jackson was trying to produce when binding failed, or null. */
internal fun jsonTargetType(error: Throwable): Class<*>? =
    causes(error).firstNotNullOfOrNull { call(it, "getTargetType") as? Class<*> }

private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }

private fun call(target: Any?, method: String): Any? = target?.let {
    runCatching { it.javaClass.getMethod(method).invoke(it) }.getOrNull()
}
