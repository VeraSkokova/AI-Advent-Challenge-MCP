# AI Reviewer Tool - User Guide & FAQ / Руководство пользователя

## Installation / Установка
The tool requires Java 17+ and valid Yandex Cloud API keys.
Environment variables needed: `YANDEX_API_KEY`, `YANDEX_FOLDER_ID`.

## Troubleshooting / Устранение проблем

### "Index not found" or "Empty context" / "Индекс не найден"
**Cause:** The RAG index hasn't been built.
**Solution:** Run the tool with `index` command: `./gradlew run --args="index"`.

### "Authorization failed" (401) / "Ошибка авторизации"
**Cause:** Invalid or expired API Key. (Неверный или просроченный API ключ)
**Solution:** Check your `local.properties` or ENV variables. (Проверьте `local.properties` или переменные окружения)

### "GitHub API Error" / "Ошибка GitHub API"
**Cause:** `GITHUB_TOKEN` is missing or has insufficient scopes.
**Solution:** Ensure the token has `repo` scope for private repositories.

## Compatibility / Совместимость
- **MacOS:** Fully supported.
- **Windows:** Supported (use `./gradlew.bat`).
- **Java Version:** Minimum Java 17, recommended Java 21.

## Subscriptions / Подписки
- **FREE:** Local review only.
- **PRO:** GitHub PR review support and priority support.
