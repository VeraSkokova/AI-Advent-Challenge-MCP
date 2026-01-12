# 🎄 AI Advent Challenge - Day 20: Developer Assistant (RAG + MCP)

Это финальная версия AI-ассистента для разработчиков, объединяющая **RAG (Retrieval-Augmented Generation)** для работы с документацией и **MCP (Model Context Protocol)** для управления окружением.

Ассистент умеет не только выполнять задачи (запуск эмулятора, проверка крипты), но и отвечать на вопросы по коду проекта, понимая текущий контекст Git.

## 🌟 Новые возможности (Day 20)

### 🧠 Developer Assistant (RAG + Git)
Ассистент проиндексировал код проекта и документацию. Теперь он знает:
- Как работает архитектура проекта.
- В какой ветке вы находитесь.
- Какие файлы были изменены.

**Команды:**
- `/help` — показать краткую сводку (README summary + git status).
- `/help <вопрос>` — найти ответ в документации и коде (например: `/help как добавить новый MCP tool?`).

### 🤖 Android Environment MCP (Day 15)
Полное управление Android-разработкой через AI:
- `start_emulator` / `wait_for_device` — запуск AVD.
- `install_apk` — установка с флагом reinstall.
- `start_app` — запуск приложения.
- `list_devices`, `get_logcat` — диагностика.

## 🏗 Архитектура MCP

Система оркестрирует **6 независимых MCP серверов**:

1.  **DeveloperAssistantMCPServer** (Kotlin, In-Process) 🆕
    *   *Role:* Знания о проекте и Git.
    *   *Tools:* `ask_project_docs`, `git_status`, `help_overview`.
2.  **AndroidEnvironmentMCPServer** (Kotlin, In-Process)
    *   *Role:* Управление Android SDK/ADB.
3.  **CryptoCurrencyMCPServer** (Kotlin, In-Process)
    *   *Role:* Данные о криптовалютах (CoinCap API).
4.  **SummarizationMCPServer** (Kotlin, In-Process)
    *   *Role:* Форматирование данных в отчеты.
5.  **ReminderMCPServer** (Kotlin, In-Process)
    *   *Role:* Управление напоминаниями (CRON).
6.  **Filesystem Server** (Node.js, External Process)
    *   *Role:* Запись файлов в папку `mcp-output/`.

## 🚀 Как запустить

### Предварительные требования
*   JDK 17+
*   Node.js (для Filesystem MCP)
*   Android SDK (для Android MCP)
*   Git (установлен и доступен в PATH)

### Настройка ключей
Создайте файл `local.properties` в корне проекта (или используйте ENV переменные):

```properties
yandex.api.key=<ваш_api_ключ>
yandex.folder.id=<ваш_folder_id>
coincap.api.key=<опционально>
```

### Запуск
```bash
./gradlew run
```

## 💬 Примеры сценариев

**1. Исследование проекта:**
> /help
*(Покажет описание проекта, текущую ветку и измененные файлы)*

> /help где находится логика обработки команд?
*(Найдет ответ в коде через RAG)*

**2. Android разработка:**
> Запусти эмулятор Pixel_5 и установи приложение app-debug.apk
*(Агент запустит эмулятор, дождется загрузки, установит APK и запустит его)*

**3. Крипто-отчет:**
> Проверь курс BTC, сделай саммари и сохрани в файл report.txt
*(Crypto -> Summarization -> Filesystem pipeline)*

## 🛠 Стек технологий
*   **Kotlin**
*   **Model Context Protocol (MCP)**
*   **YandexGPT** (LLM & Embeddings)
*   **Vector Search** (Cosine Similarity)
*   **ProcessBuilder** (Git & ADB integration)
