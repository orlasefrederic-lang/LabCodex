# Сборка NetStrazh 2.0

Проверены исходники `Netstrazh ver 2.0.zip`, ветка `Netstrazh`, коммит
`5edab39c4301f45940155552a7e6dca868c63541`. Исходники приложения не менялись:
все **59 оригинальных файлов** совпали с исходным SHA-256-манифестом после обеих
сборок. Выход сборки, проектный кэш и дополнительные тесты вынесены за дерево исходников.

## Версия и среда

- JDK21.0.13, Gradle8.11.1, Android Gradle Plugin8.7.3, Kotlin2.0.21.
- Новое значение versionName читается из файла `VERSION`: **2.0**.
- `BUILD_NUMBER` задаёт только versionCode=`100 + BUILD_NUMBER`. Для проверки
  выбран **BUILD_NUMBER=20**, поэтому versionCode **120** — выше установленной1.1.15/code115.
  Это номер проверочной сборки, а не утверждение о номере пользовательского CI-релиза.
- minSdk29, targetSdk34, compileSdk35. Собрана debug-версия приложения;
  release/R8 отдельно не проверялась.

## Исходный проект

```sh
bash run-gradle.sh testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

Результат: **BUILD SUCCESSFUL**,1мин40с,74 задачи выполнены.
[Лог оригинальной сборки](logs/build-original.log).

- **36/36 модульных тестов прошли**, без ошибок и пропусков:
  DnsPipeTest8, PacketTest13, SocksTest12, новый UpstreamDeadlineTest3.
- Новые UpstreamDeadlineTest проверяют успешный ответ внутри бюджета,
  общий бюджет SOCKS greeting+CONNECT и верхний срок полной DNS-операции.
  Это JVM-тесты; они не заменяют работу приложения на Android.
- Lint: **0 ошибок /26 предупреждений**. Категории: SetTextI18n18,
  StaticFieldLeak2, GradleDependency3, OldTargetApi1, DataExtractionRules1,
  ObsoleteSdkInt1. Число и категории совпадают с1.1.15.
- GradleDependency относится к AndroidX-библиотекам тестирования.
  StaticFieldLeak сам по себе не доказывает удержание Activity:
  в проверенном `Apps.ctx` присваивается applicationContext.

Материалы: [сводка unit](logs/unit-test-summary.json), [XML unit](unit-test-results),
[сводка lint](logs/lint-summary.json), [XML lint](lint-results-debug.xml).

## Внешние тесты для эмулятора

Через внешний `extra-tests.init.gradle` перенесены пять классов из1.1.15:
DomainRegressionTest, LocalRuntimeTest, IndependentClientTest, RealV2rayTest,
SettledRealV2rayTest. API приложения совместимы; исправлений исходников приложения
для сборки тестов не потребовалось.

Изменения тестового стенда:

1. Путь корня изменён на `/workspace/netstrazh-v2.0-review`.
2. IndependentClientTest сохраняет результат в `files/independent-client-v20.txt`.
3. Его ожидание после восстановления усилено: `captureVerifiedAt` сверяется с
   **текущим** `Live.startedAt`, который должен быть положительным и не старше
   сохранённого времени перезапуска. Это не позволяет принять проверку прошлого
   engine после очередного rebuild. Первоначальные проверки сразу после старта,
   все ошибки, точные payload, UID/PID, адреса источника и новый учёт сохранены.

```sh
bash run-gradle.sh -I extra-tests.init.gradle assembleDebug assembleDebugAndroidTest
```

Результат: **BUILD SUCCESSFUL**,24с;4 задачи выполнены,56 актуальны.
[Лог сборки внешних тестов](logs/build-review-tests.log).

APK готовы:

- `build-output/app/outputs/apk/debug/app-debug.apk`
- `build-output/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

[Контрольные суммы APK](logs/build-artifact-sha256.json),
[контроль исходников](logs/source-integrity-build.json), [манифест](source-manifest.json).

Сборка тестов не означает их выполнение. Фактические результаты эмулятора
указываются в итоговом отчёте. В2.0 `running=true` всё ещё может предшествовать
успешной проверке, но UI теперь показывает состояние проверки, а не прежнюю
обычную зелёную надпись; смысл раннего сбоя надо описывать с учётом этого изменения.
Оригинальный `E2eTest` теперь ждёт `captureVerifiedAt` в BeforeClass;
старые внешние ранние тесты сохраняют отдельную проверку окна до этого события.
Оригинальный t18_unknownOwnersCanBeBlocked также обращается к example.com
по TCP, поэтому он не является полностью локальным тестом. Для этого стенда
добавлен отдельный UnknownUidReviewTest, описанный ниже.

## Локальная проверка правил неизвестного UID

Добавлен внешний `UnknownUidReviewTest` из2 сценариев, исходный E2eTest сохранён.
Первый явно задаёт правило BLOCK_ALL для `uid:-1`, проверяет Wi-Fi/mobile,
отсутствие влияния на известный UID и отсутствие исключения из VPN. Затем
известный UID передаёт точные TCP/UDP-пакеты локальным ответчикам и проверяется
свежий учёт; открывается AppActivity для UID−1. Второй сценарий проверяет
раздельность unknown/known/global доменных правил и удаление отдельного правила.
Оба восстанавливают настройки через RuntimeFixture.

Перед этими проверками ожидается свежая успешная самопроверка текущего engine: эта
диагностика касается правил, а окно после старта проверяется отдельными классами.
**Тест не заставляет Android возвращать неизвестного владельца настоящего сокета**
и не доказывает поведение гонки ownerUid на сетевом пути. Второй сценарий проверяет
таблицу доменных правил, не отправляет DNS-запросы.

Лог дополнительной сборки: [build-unknown-uid-tests.log](logs/build-unknown-uid-tests.log).

Дополнительная сборка успешна:16с,4 задачи выполнены/39 актуальны.
Все59 оригинальных файлов после неё неизменны; текущий APK включает6 внешних классов.
Предыдущие хеши APK сохранены в [build-artifact-before-unknown-uid-sha256.json](logs/build-artifact-before-unknown-uid-sha256.json).
