# openrewrite-migrate

Скилл для coding-агента (формат SKILL.md: Claude Code и другие агенты, которые его понимают): миграция Java- и Kotlin-проектов на новые версии Java, Spring Boot, Jakarta EE, JUnit и других библиотек, а также внутренних библиотек с ломающими изменениями.

Принцип: **рецепты OpenRewrite делают основную работу детерминированно, LLM доводит только хвост, а правоту определяют компилятор и тесты.** Скилл задаёт порядок работы и правила: не ослаблять тесты, не выбирать версии на глаз, читать выжимки из логов, а не логи целиком. Коммиты размечаются `recipe:` / `llm:`, в конце — отчёт о миграции с «кандидатами в рецепты».

## Что внутри

```
SKILL.md      порядок работы: baseline → план → рецепты → уборка → хвост → отчёт
reference/    Maven, Gradle, совместимость версий, поиск рецептов, каталог внутренних рецептов
scripts/
  RunRecipes.java   запускает рецепты, не меняя сборку проекта, и коммитит результат
  BuildReport.java  собирает проект и вместо лога отдаёт JSON с ошибками, сгруппированными по причине
  CheckDiff.java    pre-commit-хук: не даёт закоммитить удалённые тесты, @Disabled и убранные проверки
  rewrite-init.gradle
```

Скрипты запускаются как `java Script.java` (JDK 17+), сами находят Maven или Gradle и wrapper, работают с многомодульными проектами.

## Установка

Для Claude Code:

```bash
git clone https://github.com/vyatich/openrewrite-migrate.git ~/.claude/skills/openrewrite-migrate
```

или в `.claude/skills/` конкретного проекта. Дальше достаточно дать задачу: «обнови проект на Spring Boot 4 и Java 25, сборка и тесты должны остаться зелёными».

## Демо

Полный пример с замерами «со скиллом / без скилла» — [joker-migrator](https://github.com/vyatich/joker-migrator): сервис на Spring Boot 2.7 / Java 11 и внутренняя библиотека с рецептом миграции. Скилл для команд библиотек, которые пишут такие рецепты, — [openrewrite-recipe-author](https://github.com/vyatich/openrewrite-recipe-author).

## Ссылки

- Telegram: https://t.me/jvmbrain
- YouTube: https://www.youtube.com/@jvmbrain

## Лицензия

Apache License 2.0 — см. [LICENSE](LICENSE). Рецепты `rewrite-spring` и `rewrite-migrate-java`, которые скачиваются при запуске, распространяются под Moderne Source Available License.
