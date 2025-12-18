# 🎄 AI Advent Challenge 2024 - Day 14: MCP Orchestration

В этом задании реализована **оркестрация нескольких независимых MCP серверов**.
AI-агент выступает в роли дирижера, вызывая инструменты из разных серверов в нужной последовательности (Chain of Thought / Pipeline).

## 🏗 Архитектура

Система состоит из 4-х компонентов, работающих совместно:

1.  **CryptoCurrencyMCPServer** (Kotlin, In-Process)
    *   *Role:* Источник данных.
    *   *Tool:* `check_crypto_rates` — получает реальные курсы через CoinCap API.
2.  **SummarizationMCPServer** (Kotlin, In-Process)
    *   *Role:* Обработка данных (Business Logic).
    *   *Tool:* `summarize_data` — превращает сырой JSON в красивый текстовый отчет (без использования LLM).
3.  **ReminderMCPServer** (Kotlin, In-Process)
    *   *Role:* Управление состоянием (Legacy).
    *   *Tools:* `add_reminder`, `list_reminders` и др.
4.  **Filesystem Server** (Node.js, External Process)
    *   *Role:* Ввод/Вывод (Side Effects).
    *   *Tool:* `write_file` — сохраняет результаты на диск. Запускается через `npx`.

Агент (**YandexAIAgent**) динамически собирает список инструментов от всех серверов и формирует System Prompt.

## 🚀 Как запустить

### Предварительные требования
*   JDK 17+
*   Node.js (для npx)
*   Yandex Cloud API Key

### Настройка
Установите переменные окружения:

Linux / macOS
export YANDEX_API_KEY="<ваши_данные>"
export YANDEX_FOLDER_ID="<ваши_данные>"

Windows (PowerShell)
$env:YANDEX_API_KEY="<ваши_данные>"
$env:YANDEX_FOLDER_ID="<ваши_данные>"

### Запуск
./gradlew run

### Пример сценария (Demo)
В консоли введите команду, требующую цепочки действий:

Проверь курсы BTC и ETH, сформируй отчет и сохрани его в файл crypto_report.txt

**Ожидаемый результат:**
1. Агент запросит курсы (`crypto-server`).
2. Агент отформатирует JSON в текст (`summarization-server`).
3. Агент сохранит файл в папку `./mcp-output/` (`filesystem-server`).
4. Вы увидите файл `mcp-output/crypto_report.txt`.

## 🛠 Стек технологий
*   **Kotlin**
*   **Model Context Protocol (MCP) SDK** (0.7.4)
*   **YandexGPT** (LLM)
*   **CoinCap API**
*   **Stdio Transport** (для коммуникации с Node.js процессом)