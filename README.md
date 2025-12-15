# 🎄 AI Advent Challenge — Day 11: Connecting to MCP

В этом задании реализовано подключение Kotlin-клиента к **Model Context Protocol (MCP)** серверу через стандартный ввод-вывод (Stdio).

В качестве тестового сервера используется эталонный **Filesystem Server** (`@modelcontextprotocol/server-filesystem`), запускаемый через `npx`.

## 📋 Задание
1. Установить MCP SDK для Kotlin.
2. Поднять MCP-сервер (локальный вариант через stdio).
3. Написать код, который создает соединение и получает список доступных инструментов (Tools).

## 🛠 Технологии
- **Language:** Kotlin 2.0+
- **Build System:** Gradle (Kotlin DSL)
- **Libraries:**
    - `io.modelcontextprotocol:kotlin-sdk` (Official SDK)
    - `io.ktor:ktor-client-cio` (HTTP Client engine)
    - `kotlinx-coroutines`
- **External Tools:** Node.js & npx (для запуска JS-based MCP серверов)

## 🚀 Как запустить

### Предварительные требования
1. **JDK 17+** установлен.
2. **Node.js** установлен (проверьте командой `node -v` и `npx -v`).
    - *Важно для Windows:* При установке Node.js убедитесь, что выбрана опция "Add to PATH".

### Запуск из IntelliJ IDEA
1. Откройте файл `src/main/kotlin/ru/skokova/aiadventchallenge/Day11McpTest.kt`.
2. Нажмите зеленую иконку **Run** (▶) рядом с функцией `main`.

### Запуск через Gradle
./gradlew run

text

## 🧩 Реализация

Код выполняет следующие действия:
1. **Запуск подпроцесса:** Автоматически запускает команду `npx -y @modelcontextprotocol/server-filesystem .` через `ProcessBuilder`.
2. **Транспорт:** Инициализирует `StdioClientTransport`, связывая потоки ввода/вывода процесса с клиентом.
    - *Fix:* Использует `asSource().buffered()` и `asSink().buffered()` из `kotlinx-io` для корректной работы с потоками.
3. **Handshake:** Метод `client.connect()` автоматически выполняет инициализацию протокола (отправляет `initialize` и ожидает `initialized`).
4. **Запрос:** Метод `client.listTools()` запрашивает у сервера доступные инструменты.

## ✅ Ожидаемый результат

В консоли должен появиться список инструментов для работы с файловой системой:

🎄 AI Advent Challenge - Day 11
🚀 Starting MCP Server process...
🔄 Connecting...
✅ Connected!

🎉 === AVAILABLE MCP TOOLS === 🎉
🛠 read_file
📝 Read the complete contents of a file from the file system.
🛠 read_multiple_files
📝 Read multiple files from the file system.
🛠 write_file
📝 Create a new file or completely overwrite an existing file.
...