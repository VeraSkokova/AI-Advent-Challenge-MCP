# AI Reviewer Tool - User Guide & FAQ

## Installation
The tool requires Java 17+ and valid Yandex Cloud API keys.
Environment variables needed: `YANDEX_API_KEY`, `YANDEX_FOLDER_ID`.

## Troubleshooting

### "Index not found" or "Empty context"
**Cause:** The RAG index hasn't been built.
**Solution:** Run the tool with `index` command: `./gradlew run --args="index"`.

### "Authorization failed" (401)
**Cause:** Invalid or expired API Key.
**Solution:** Check your `local.properties` or ENV variables.

### "GitHub API Error"
**Cause:** `GITHUB_TOKEN` is missing or has insufficient scopes.
**Solution:** Ensure the token has `repo` scope for private repositories.

## Compatibility
- **MacOS:** Fully supported.
- **Windows:** Supported (use `./gradlew.bat`).
- **Java Version:** Minimum Java 17, recommended Java 21.

## Subscriptions
- **FREE:** Local review only.
- **PRO:** GitHub PR review support and priority support.
