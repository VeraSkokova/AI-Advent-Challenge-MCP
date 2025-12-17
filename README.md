# 🎄 AI Advent Challenge — Day 13: Reminder MCP Server with AI Agent

**Standalone MCP Server** с встроенным планировщиком задач на основе cron-выражений и AI-агентом на базе **YandexGPT API**.

## 🎯 Описание

Система автоматически выполняет напоминания по расписанию cron, передавая команды в AI-агента, который:
1. Анализирует команду через YandexGPT
2. Определяет какие MCP tools нужно вызвать
3. Выполняет tools и собирает результаты
4. Генерирует человекочитаемую сводку для desktop notification

### Архитектура

```
┌─────────────────────────────────────────────────────────┐
│           CRON SCHEDULER (работает 24/7)                │
│   • Бесконечный цикл while(true) + delay(60 сек)       │
│   • Проверяет каждую минуту: reminder.nextExecution     │
│   • Когда время наступило → вызывает AI Agent           │
└────────────────────┬────────────────────────────────────┘
                     │ executeCommand(reminder.command)
                     ↓
┌─────────────────────────────────────────────────────────┐
│              YANDEX GPT AI AGENT                        │
│  1. Получает команду: "Проверь курсы BTC и ETH..."     │
│  2. Динамически получает список tools из MCP Server     │
│  3. Решает какие MCP tools вызвать                      │
│  4. Вызывает tools и собирает результаты                │
│  5. Генерирует человекочитаемый summary                 │
│  6. Возвращает summary → Scheduler                      │
└────────────────────┬────────────────────────────────────┘
                     │ вызовы tools
                     ↓
┌─────────────────────────────────────────────────────────┐
│                  MCP SERVER (Tools)                     │
│  • list_reminders, add_reminder, remove_reminder        │
│  • get_stats, check_crypto_rates                        │
└─────────────────────────────────────────────────────────┘
                     ↓
┌─────────────────────────────────────────────────────────┐
│             NOTIFICATION SERVICE                        │
│  • Desktop notification (notify-send / osascript)       │
│  • Logging в logs/scheduler.log                         │
└─────────────────────────────────────────────────────────┘
```

## 🛠 Технологии

- **Language:** Kotlin 2.2.20
- **Build System:** Gradle (Kotlin DSL)
- **MCP SDK:** `io.modelcontextprotocol:kotlin-sdk:0.7.4`
- **HTTP Client:** Ktor 2.3.7 (для YandexGPT и CoinCap API)
- **Cron:** `com.cronutils:cron-utils:9.2.1`
- **Coroutines:** kotlinx-coroutines-core 1.9.0
- **Logging:** SLF4J + Logback

## 📦 MCP Tools

Сервер предоставляет 5 MCP tools:

1. **add_reminder** - Создать новое напоминание с cron-расписанием
   - Параметры: `title`, `command`, `cronExpression`

2. **list_reminders** - Получить список напоминаний
   - Параметры: `status` ("active" или "all")

3. **remove_reminder** - Удалить напоминание по ID
   - Параметры: `id`

4. **get_stats** - Получить статистику выполнения задач
   - Параметры: нет

5. **check_crypto_rates** - Получить текущие курсы криптовалют (CoinCap API v3)
   - Параметры: `coins` (массив, например: ["bitcoin", "ethereum"])

## 🚀 Как запустить

### Предварительные требования

1. **JDK 17+** установлен
2. **Yandex Cloud Account** с настроенным API ключом
3. **CoinCap API Key** (опционально, но рекомендуется для увеличения rate limits)

### Шаг 1: Настройка конфигурации

Создайте файл `local.properties` в корне проекта:

```properties
YANDEX_API_KEY=your_yandex_api_key_here
YANDEX_FOLDER_ID=your_folder_id_here
COINCIP_API_KEY=your_coincap_api_key_here
```

**Альтернатива:** Установите переменные окружения (они имеют приоритет над local.properties):

```bash
export YANDEX_API_KEY="your_key"
export YANDEX_FOLDER_ID="your_folder"
export COINCIP_API_KEY="your_coincap_key"
```

#### Где получить ключи:

- **Yandex Cloud API Key:** https://console.cloud.yandex.ru/folders/YOUR_FOLDER/service-accounts
- **Yandex Folder ID:** https://console.cloud.yandex.ru/ (в URL после `/folders/`)
- **CoinCap API Key:** https://coincap.io/

### Шаг 2: Запуск сервера

```bash
./gradlew run
```

После запуска вы увидите:

```
═══════════════════════════════════════════
   Reminder MCP Server with AI Agent      
═══════════════════════════════════════════
🚀 Scheduler running in background (24/7)
💬 CLI interface ready
═══════════════════════════════════════════

Commands: add | list | test <command> | exit

> 
```

## 💻 CLI Команды

### 1. Добавить напоминание

```bash
> add "Курс криптовалют" "Проверь текущие курсы Bitcoin и Ethereum" "0 * * * *"
```

Формат: `add "Название" "Команда для AI" "Cron выражение"`

**Примеры cron выражений:**
- `0 * * * *` - каждый час
- `0 9 * * *` - каждый день в 9:00
- `0 18 * * 5` - каждую пятницу в 18:00
- `*/5 * * * *` - каждые 5 минут

### 2. Показать все напоминания

```bash
> list
```

### 3. Протестировать AI-агента

```bash
> test Проверь курсы BTC и ETH
```

Агент:
1. Получит список доступных tools динамически
2. Проанализирует команду через YandexGPT
3. Вызовет `check_crypto_rates` с параметрами `["bitcoin", "ethereum"]`
4. Сгенерирует сводку: "💰 Bitcoin $95,234 (+2.3%), Ethereum $3,456 (-0.8%)"

### 4. Выход

```bash
> exit
```

## 📝 Примеры использования

### Пример 1: Ежечасная проверка криптовалют

```bash
> add "Курс крипты" "Проверь текущие курсы Bitcoin и Ethereum, сравни с открытием дня" "0 * * * *"
```

Каждый час:
1. Scheduler запускает AI Agent с командой
2. Agent вызывает `check_crypto_rates(["bitcoin", "ethereum"])`
3. Генерируется summary: "📈 Bitcoin $95,234 (+2.3%), Ethereum $3,456 (-0.8%)"
4. Desktop notification показывает результат

### Пример 2: Утренняя сводка задач

```bash
> add "Утренняя сводка" "Покажи все активные напоминания и статистику выполнений" "0 9 * * *"
```

Каждый день в 9:00:
1. Agent вызывает `list_reminders(status="active")` и `get_stats()`
2. Summary: "📊 У вас 7 активных задач, выполнено 45 раз. Сегодня запланировано: ..."

### Пример 3: Еженедельный отчёт

```bash
> add "Недельная сводка" "Посчитай сколько задач выполнено за неделю" "0 18 * * 5"
```

## 🏗 Структура проекта

```
reminder-mcp-server/
├── build.gradle.kts
├── src/main/kotlin/ru/skokova/aiadventchallenge/
│   ├── Main.kt                      # Entry point + CLI
│   ├── storage/
│   │   ├── Reminder.kt              # Data class
│   │   └── ReminderStorage.kt       # JSON CRUD с mutex
│   ├── scheduler/
│   │   ├── CronParser.kt            # Парсинг cron
│   │   └── ReminderScheduler.kt     # Бесконечный цикл
│   ├── notifications/
│   │   └── NotificationService.kt   # Desktop alerts
│   ├── ai/
│   │   ├── YandexGPTClient.kt       # HTTP клиент
│   │   └── YandexAIAgent.kt         # Главный AI агент
│   ├── mcp/
│   │   └── ReminderMCPServer.kt     # Регистрация tools
│   ├── coincap/
│   │   └── CoinCapClient.kt         # CoinCap API v3
│   └── utils/
│       ├── PropertiesUtil.kt        # Загрузка конфига
│       └── KeyUtils.kt              # ENV/properties
├── src/main/resources/
│   └── logback.xml
├── data/
│   └── reminders.json
└── logs/
    └── scheduler.log
```

## 🔧 Как это работает

### 1. Scheduler Loop (24/7)

```kotlin
while (true) {
    val now = System.currentTimeMillis()
    val dueReminders = storage.getDueReminders(now)
    
    dueReminders.forEach { reminder ->
        val agentResponse = aiAgent.executeCommand(reminder.command)
        notificationService.notify(reminder.title, agentResponse.summary)
        storage.updateExecution(reminder.id, now)
    }
    
    delay(60_000) // 60 секунд
}
```

### 2. AI Agent Workflow

```kotlin
suspend fun executeCommand(command: String): AgentResponse {
    // 1. Динамически получаем tools из MCP Server
    val tools = mcpServer.listTools()
    
    // 2. Строим prompt с описанием tools
    val systemPrompt = buildSystemPrompt(tools)
    
    // 3. YandexGPT анализирует команду
    val response = yandexGPT.chat(systemPrompt, command)
    
    // 4. Парсим JSON с tool calls
    val toolCalls = parseToolCalls(response)
    
    // 5. Выполняем tools
    val results = toolCalls.map { executeTool(it) }
    
    // 6. Генерируем summary
    val summary = yandexGPT.chat("Создай краткую сводку", results)
    
    return AgentResponse(summary, toolCalls, results)
}
```

## 🐛 Troubleshooting

### Проблема: "YANDEX_API_KEY not set"

**Решение:** Создайте `local.properties` или установите переменные окружения.

### Проблема: Desktop notifications не работают

**Linux:** Установите `notify-send`:
```bash
sudo apt install libnotify-bin
```

**macOS:** osascript встроен в систему

**Windows:** SystemTray работает из коробки

### Проблема: CoinCap API возвращает ошибку

**Решение:** Проверьте что используется правильный URL:
- ✅ `https://rest.coincap.io/v3/assets`
- ❌ `https://api.coincap.io/v2/assets`

## 📚 Дополнительные ресурсы

- [Model Context Protocol Documentation](https://modelcontextprotocol.io)
- [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
- [Yandex Cloud GPT API](https://cloud.yandex.ru/docs/foundation-models/)
- [CoinCap API v3](https://docs.coincap.io/)
- [Cron Expression Guide](https://crontab.guru/)

## 📄 Лицензия

MIT License
