# Руководство по деплою (Deploy Guide)

Наш проект автоматически собирается через GitHub Actions. Это руководство описывает, как развернуть полученный артефакт на сервере или локальной машине.

## 1. Где взять сборку?
1. Зайдите во вкладку **Actions** в репозитории GitHub.
2. Откройте последний успешный запуск workflow "Build and Package MCP Server".
3. Внизу страницы в разделе **Artifacts** скачайте файл `mcp-server-release`.

## 2. Установка и запуск

### Требования
* Java 17 или выше (`java -version`).
* Доступ к интернету для API запросов.

### Инструкция

1. **Распаковка:**
   Скачанный zip-архив содержит папку с версией приложения (например, `AI-Advent-Challenge-MCP-1.0-SNAPSHOT`).
   ```bash
   unzip mcp-server-release.zip
   unzip AI-Advent-Challenge-MCP-1.0-SNAPSHOT.zip
   cd AI-Advent-Challenge-MCP-1.0-SNAPSHOT
   ```

2. **Настройка окружения:**
   В папке `bin` создайте или убедитесь в наличии переменных окружения.
   Вы можете создать файл `local.properties` в корне распакованной папки, если приложение поддерживает чтение из него, или экспортировать переменные:
   ```bash
   export YANDEX_API_KEY="ваш_ключ"
   export YANDEX_FOLDER_ID="ваш_folder_id"
   export GITHUB_TOKEN="ваш_токен"
   ```

3. **Запуск:**
   * **Linux/macOS:**
     ```bash
     ./bin/AI-Advent-Challenge-MCP
     # Или с аргументами:
     ./bin/AI-Advent-Challenge-MCP manage
     ```
   * **Windows:**
     ```cmd
     bin\AI-Advent-Challenge-MCP.bat
     ```

## 3. Автоматический запуск (Linux Systemd)

Для того чтобы бот работал постоянно как сервис, создайте файл `/etc/systemd/system/mcp-server.service`:

```ini
[Unit]
Description=AI Advent Challenge MCP Server
After=network.target

[Service]
User=your_user
WorkingDirectory=/path/to/AI-Advent-Challenge-MCP-1.0-SNAPSHOT
ExecStart=/path/to/AI-Advent-Challenge-MCP-1.0-SNAPSHOT/bin/AI-Advent-Challenge-MCP manage
Restart=always
Environment="YANDEX_API_KEY=xxx"
Environment="YANDEX_FOLDER_ID=yyy"
Environment="GITHUB_TOKEN=zzz"

[Install]
WantedBy=multi-user.target
```

Затем выполните:
```bash
sudo systemctl daemon-reload
sudo systemctl enable mcp-server
sudo systemctl start mcp-server
```
