package ru.skokova.aiadventchallenge.utils

import java.util.Properties

fun getEnvOrProperty(key: String, properties: Properties?): String {
    // 1. Ищем в ENV (приоритет)
    val envValue = System.getenv(key)
    if (!envValue.isNullOrBlank()) return envValue

    // 2. Ищем в properties файле
    val propValue = properties?.getProperty(key)
    if (!propValue.isNullOrBlank()) return propValue

    // 3. Падаем, если не нашли
    throw IllegalStateException("Missing configuration: $key. Please set it in ENV or local.properties")
}
