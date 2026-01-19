# День 24: Реальная задача — CI/CD деплой на localhost 🚀

## Задание
Придумать **реальную задачу** и реализовать приложение, которое будет её выполнять.  
Сделать **пайплайн** (может не работать из-за отсутствия ключей в публичном репо, но код должен быть).

## Что реализовано

### 1. GitHub Actions Workflow (CI)
- Workflow `.github/workflows/ci.yml` автоматически:
    - собирает проект при push в `day24` или `main`;
    - запускает тесты (`./gradlew build`);
    - упаковывает приложение в ZIP-дистрибутив (`distZip`);
    - загружает артефакт `mcp-server-release` в GitHub Actions.

### 2. Режим "server"
- Добавлена команда `./gradlew run --args="server"`, которая запускает HTTP-сервер на порту **8080**.
- Эндпоинты:
    - `GET /` — возвращает статус "🚀 Server is RUNNING!".
    - `GET /health` — возвращает "OK".

### 3. MCP Tool: `deploy_to_localhost`
- Новый MCP-сервер `DeployMCPServer` с инструментом `deploy_to_localhost`:
    - **Триггерит** GitHub Actions workflow через API;
    - **Ожидает** завершения сборки (polling);
    - **Скачивает** ZIP-артефакт (обработка редиректов GitHub → Azure Blob);
    - **Распаковывает** вложенные архивы (GitHub Actions упаковывает `distZip` ещё раз);
    - **Запускает** сервер локально (`bin/ai-advent-mcp server`);
    - Логи записываются в `deploy/server.log`.

### 4. Интеграция с AI Team Manager
- В режиме `manage` можно попросить бота:
    - "Задеплой на локалхост"
    - "Deploy to localhost branch day24"
- Бот сам вызовет нужный инструмент, дождётся сборки и запустит сервер.

## Как запустить

### Шаг 0: Настройка токена
Для работы деплоя нужен **GitHub Personal Access Token** с правами:
- `repo` (или `workflow` + `actions:read`)

Установи переменную окружения:
```bash
export GITHUB_TOKEN="ghp_..."
```
Или добавь в local.properties:

```text
GITHUB_TOKEN=ghp_...
```

Шаг 1: Запуск менеджера
bash
./gradlew run --args="manage"
Шаг 2: Команда деплоя
В консоли менеджера напиши:

```text
Задеплой последнюю версию на локалхост
```

Или на английском:

```text
Deploy to localhost
```

Шаг 3: Проверка
После успешного деплоя:

```bash
curl http://localhost:8080/

# Ожидается: 🚀 AI Advent Challenge MCP Server is RUNNING! Deployed via GitHub Actions.

curl http://localhost:8080/health

# Ожидается: OK
```

Или открой в браузере: http://localhost:8080

# Архитектура решения
- `GitHubClient` (расширен)
- Добавлены методы для работы с GitHub Actions API:
  - `triggerWorkflow()` — запуск workflow через `workflow_dispatch`; 
  - `getLatestRun()` — получение последнего run для проверки статуса; 
  - `listArtifacts()` — список артефактов run'а; 
  - `downloadArtifact()` — скачивание ZIP с обработкой редиректа GitHub → Azure. 
- `DeployMCPServer`
  - Управляет всем процессом деплоя (trigger → poll → download → unzip → start). 
  - Использует `java.net.HttpURLConnection` для скачивания артефакта (обход проблем Ktor с редиректами и нестандартными Content-Type).
- `Main.kt` (новый режим)
  - `server` — запуск Ktor HTTP-сервера (то, что мы деплоим). 
  - `manage` — теперь интегрирован с DeployMCPServer.

## Отладка

Логи деплоя

Смотри вывод консоли — там будут шаги:

```text
🚀 Triggering workflow...
⏳ Waiting for workflow run to start...
🔄 Run #21131557965 Status: in_progress (Attempt 5/30)
📦 Build successful! Fetching artifacts...
⬇️ Downloading artifact...
📂 Unzipping...
🚀 Starting deployed server...
✅ Deployment Successful! Server PID: 12345
```

Логи запущенного сервера

```bash
cat deploy/server.log
```

Если сервер не стартовал, проверь:

- Права на выполнение скрипта (`chmod +x deploy/**/bin/*`).

- Наличие Java 17+ на машине.

- Переменные окружения (Yandex API Key, если нужны для работы сервера).

Что дальше?

- **Production deploy**: Вместо localhost можно деплоить на VPS через SSH (добавить шаг `scp` + `systemctl restart`).

- **Docker**: Собирать Docker-образ вместо ZIP и пушить в Container Registry.

- **Kubernetes**: Helm-чарт для деплоя в кластер.

- **Rollback**: Хранить несколько версий и откатываться при ошибке.