# 🎄 AI Advent Challenge: Day 21 - AI Code Reviewer

Автоматический AI-агент для ревью кода, построенный на базе **MCP** (Model Context Protocol) и **RAG** (Retrieval Augmented Generation). Агент анализирует изменения в коде (локальные или PR на GitHub), сверяет их с документацией проекта и стандартами кодирования, и выдает структурированный отчет с замечаниями.

---

## 🚀 Основные возможности

*   **Гибридный пайплайн**: Объединяет Git-инструменты, RAG-поиск и LLM анализ.
*   **Два режима работы**:
    *   🕵️ **Local Review**: Анализ незакоммиченных изменений (`git diff`) в текущей директории.
    *   🌐 **Remote PR Review**: Анализ Pull Request-ов с GitHub по URL.
*   **Умный RAG**:
    *   Индексирует не только документацию (`docs/`), но и кодовую базу (`src/`).
    *   Динамически подбирает контекст на основе ключевых слов из diff-а.
*   **Strict Policy Checking**:
    *   Агент обучен ссылаться на конкретные пункты правил (Rule ID) из документации.
    *   Указывает файл и **строки** источника правил при обнаружении нарушений.

---

## 🛠 Архитектура

Проект реализован на **Kotlin** и состоит из следующих компонентов:

1.  **MCP Server (`DeveloperAssistantMCPServer`)**:
    *   Центральный узел, предоставляющий инструменты для агента.
    *   `get_pr_diff` / `fetch_github_pr_diff`: Получение изменений кода.
    *   `ask_project_docs`: Интерфейс к RAG-системе.

2.  **RAG System (`ru.skokova.aiadventchallenge.rag`)**:
    *   **Indexer**: Сканирует `.md` и `.kt` файлы, разбивает на чанки.
    *   **Vector Search**: Использует Yandex Embeddings для поиска релевантных правил.

3.  **AI Integration (`YandexGPTClient`)**:
    *   Прямая интеграция с Yandex Cloud (YandexGPT Pro).
    *   Специализированный системный промпт для роли "Senior Kotlin Reviewer".

---

## ⚙️ Настройка

Для работы требуются ключи API. Создайте файл `local.properties` в корне проекта (или используйте переменные окружения):

```properties
# Yandex Cloud (Required for LLM & Embeddings)
YANDEX_API_KEY=your_yandex_api_key
YANDEX_FOLDER_ID=your_folder_id

# GitHub (Required only for Remote PR Review)
GITHUB_TOKEN=your_github_token
```

---

## ▶️ Запуск и Использование

### 1. Индексация (Подготовка)
Перед первым запуском необходимо проиндексировать документацию и код, чтобы агент "выучил" правила:

```bash
./gradlew run --args="index"
```
*Сканирует папки `docs/` и `src/main/kotlin`, создаёт файл `rag_index.json`.*

### 2. Локальное ревью (Local Diff)
Анализирует текущие изменения в рабочей директории (то, что покажет `git diff`):

```bash
./gradlew run --args="review"
```

### 3. Ревью Pull Request (GitHub)
Анализирует внешний PR по ссылке:

```bash
./gradlew run --args="review_pr https://github.com/VeraSkokova/AI-Advent-Challenge-MCP/pull/1"
```

---

## 📂 Структура реализации (Day 21)

*   `src/main/kotlin/ru/skokova/aiadventchallenge/Main.kt` — Точка входа, реализация CLI пайплайна.
*   `src/main/kotlin/ru/skokova/aiadventchallenge/mcp/DeveloperAssistantMCPServer.kt` — Локальный MCP сервер с инструментами.
*   `src/main/kotlin/ru/skokova/aiadventchallenge/git/GitHubClient.kt` — Клиент для получения raw diff с GitHub.
*   `docs/Documentation.md` — Пример документации со стандартами кодирования (для тестирования RAG).

---

### Пример отчета агента:

```markdown
## 🚨 Violations Found

### [LOG-001] Logging Policy
**Source:** `Documentation.md:5-8`
**Violation:** The code uses `println` for logging secrets, which is strictly forbidden.
**Fix:**
// Use Logger instead
logger.info("...")
```
