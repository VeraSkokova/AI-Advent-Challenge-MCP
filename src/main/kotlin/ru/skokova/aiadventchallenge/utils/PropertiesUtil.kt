package ru.skokova.aiadventchallenge.utils

import java.io.File
import java.util.Properties

fun loadProperties(): Properties? {
    val file = File("local.properties")
    return if (file.exists()) {
        Properties().apply { load(file.inputStream()) }
    } else {
        null
    }
}
