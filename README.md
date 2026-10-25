# DP Dokument Tracker

Java-приложение для периодической проверки доступности дат записи и отправки
уведомлений в Telegram.

## Требования

- JDK 17 или новее;
- Apache Maven 3.9 или новее;
- переменные окружения `TELEGRAM_BOT_TOKEN` и `TELEGRAM_CHAT_ID` для отправки
  уведомлений.

Проект компилируется с параметром `--release 17`, поэтому полученный JAR
совместим с Java 17. Playwright уже подключён как зависимость, но на текущем
этапе приложение его не использует и браузер автоматически не запускает.

## Сборка

```bash
mvn clean verify
```

Исполняемый JAR со всеми Java-зависимостями будет создан по пути:

```text
target/dp-dokument-tracker-1.0.0-SNAPSHOT.jar
```

## Запуск приложения

```bash
export TELEGRAM_BOT_TOKEN="<token>"
export TELEGRAM_CHAT_ID="<chat-id>"
java -jar target/dp-dokument-tracker-1.0.0-SNAPSHOT.jar
```

После запуска приложение сразу начинает выполнять периодические запросы к
реальному сайту. При изменении состояния оно может отправить уведомление в
Telegram. Для остановки используйте `Ctrl+C`.

## Установка Chromium для Playwright

Установка только браузерного бинарника Chromium:

```bash
mvn exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install chromium"
```

На Linux при наличии прав администратора можно одновременно установить
необходимые системные библиотеки:

```bash
mvn exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI \
  -Dexec.args="install --with-deps chromium"
```

Установка Chromium не запускает браузер и не обращается к проверяемому сайту.

## Импорт в Eclipse

1. Выберите `File` → `Import` → `Maven` → `Existing Maven Projects`.
2. Укажите корневой каталог проекта.
3. Выберите найденный `pom.xml` и завершите импорт.

Eclipse с установленным m2e распознает `src/main/java` как каталог исходного
кода, загрузит Maven-зависимости и применит уровень языка Java 17.
