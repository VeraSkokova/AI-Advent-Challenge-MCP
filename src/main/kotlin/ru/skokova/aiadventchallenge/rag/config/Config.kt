package ru.skokova.aiadventchallenge.rag.config

import java.io.File
import java.util.Properties

object Config {
    val apiKey: String
    val folderId: String

    const val MAX_CHUNK_SIZE = 512
    const val CHUNK_OVERLAP = 100

    init {
        val file = File("local.properties")
        if (!file.exists()) {
            throw IllegalStateException("Файл local.properties не найден! Создайте его с yandex.api.key и yandex.folder.id")
        }
        val props = Properties()
        file.inputStream().use { props.load(it) }

        apiKey = props.getProperty("yandex.api.key") ?: error("Не найден yandex.api.key")
        folderId = props.getProperty("yandex.folder.id") ?: error("Не найден yandex.folder.id")
    }
}