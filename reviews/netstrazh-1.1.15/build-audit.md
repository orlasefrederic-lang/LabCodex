# Сборка NetStrazh 1.1.15

Проверка 4 ноября 2026. Исходники: `sources/Lab-claude-confident-cray-n5kkx8/NetStrazh`.
Все 57 исходных файлов из архива после сборки совпадают с исходным SHA-256-манифестом.
Выход сборки, кэш проекта и дополнительные тесты находятся вне дерева исходников.

## Оригинальный проект

Команда:

```sh
bash run-gradle.sh testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

Результат: **BUILD SUCCESSFUL**, 1 мин 24 с, 74 выполненные задачи.
Лог: [logs/build-original.log](logs/build-original.log).

- JDK 21.0.13; Gradle 8.11.1; Android Gradle Plugin 8.7.3; Kotlin 2.0.21.
- `BUILD_NUMBER=15`: versionName **1.1.15**, versionCode **115**, minSdk **29**, targetSdk **34**, compileSdk **35**.
- Собраны debug APK приложения и оригинальный androidTest APK. Release/R8-сборка отдельно не проверялась.
- **33/33 модульных теста прошли**, ошибок и пропусков нет: DnsPipeTest 8, PacketTest 13, SocksTest 12.
- Линтер: **0 ошибок, 26 предупреждений**. Категории: SetTextI18n 18, StaticFieldLeak 2, GradleDependency 3, OldTargetApi 1, DataExtractionRules 1, ObsoleteSdkInt 1.

Предупреждения совпадают по количеству и категориям с проверкой 1.1.12.
Три GradleDependency относятся только к AndroidX-библиотекам тестирования.
Оба StaticFieldLeak относятся к `Apps.ctx`; присваивается `c.applicationContext`, поэтому эти сообщения сами по себе не доказывают утечку Activity.
Линтер не доказывает корректность VPN или отсутствие уязвимостей; это отдельно проверяют анализ и эмулятор.

Результаты: [logs/unit-test-summary.json](logs/unit-test-summary.json), [unit-test-results](unit-test-results), [lint-results-debug.xml](lint-results-debug.xml), [logs/lint-summary.json](logs/lint-summary.json).

## Дополнительные регрессионные тесты

Через внешний `extra-tests.init.gradle` добавлены четыре класса из проверки 1.1.12:
DomainRegressionTest, LocalRuntimeTest, IndependentClientTest, RealV2rayTest.
Первый перенос собрался без изменений API. Затем IndependentClientTest расширен по плану проверки:

- Три последовательных выключения/включения VPN при одном и том же процессе независимого клиента.
- Уникальные метки каждого этапа; проверки нового движка, точных ответов TCP/UDP, адреса TUN, UID, свежих записей и счётчиков сохранены.
- После `awaitRunning` дана 1 секунда для первого внутреннего диагностического запроса.
- До и после этапов пишутся `captureVerifiedAt`, `captureRecoveries`, `captureBroken`, число и состояния потоков `ns-capture`.
- Результат внутри приложения: `files/independent-client-v115.txt`.
- Исходный одноцикловый тест сохранён как [artifacts/IndependentClientTest-original.txt](artifacts/IndependentClientTest-original.txt).

Команда финальной сборки:

```sh
bash run-gradle.sh -I extra-tests.init.gradle assembleDebug assembleDebugAndroidTest
```

Результат: **BUILD SUCCESSFUL**, 20 с. Лог: [logs/build-review-tests-final.log](logs/build-review-tests-final.log).
Сборка тестов не означает, что тесты выполнены; результаты эмулятора находятся в отдельном отчёте.

Финальные APK:

- `build-output/app/outputs/apk/debug/app-debug.apk`
- `build-output/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

Контрольные суммы APK: [logs/build-artifact-sha256.json](logs/build-artifact-sha256.json).
Проверка исходников: [logs/source-integrity-build.json](logs/source-integrity-build.json).

## Диагностика восстановления после первого прогона

После наблюдённой ошибки сразу после перезапуска IndependentClientTest дополнительно
проверяет восстановление: после исходных проверок ожидает до 30 секунд успешной
свежей внутренней проверки (`captureVerifiedAt >= restartedEngineStarted`,
`running`, `!captureBroken`), затем отправляет ещё одну независимую пару TCP/UDP
с меткой `settled-N`. Проверяются тот же процесс клиента, точные ответы, адрес TUN,
новые записи и счётчики. Проверки выполняются и при таймауте внутреннего
контроля; первоначальные ошибки сохраняются. В диагностику добавлены
`Live.startedAt` и `running`.

Версия теста первого прогона сохранена в
[artifacts/IndependentClientTest-first-run.kt.txt](artifacts/IndependentClientTest-first-run.kt.txt).
Лог пересборки: [logs/build-review-tests-recovery.log](logs/build-review-tests-recovery.log).

## Отдельная проверка v2rayNG после успешной внутренней проверки

Добавлен отдельный `SettledRealV2rayTest`; первоначальный `RealV2rayTest` и его
результаты сохранены. После проверки конфигурации, исключения приложения и UID
новый класс ждёт до 30 секунд: VPN работает, `captureBroken=false`,
`captureVerifiedAt >= Live.startedAt`, текущие настройки/исключение/UID соответствуют
ожидаемым. Затем выполняются те же семь тестов. В журнале фиксируются время
старта движка, последняя успешная проверка, число восстановлений и состояние
`captureBroken`. Это диагностическое разделение немедленного сбоя и состояния
после подтверждения внутреннего перехвата; оно не удаляет исходные ошибки.

Лог сборки: [logs/build-settled-v2ray-tests.log](logs/build-settled-v2ray-tests.log).
