# Сборка и тестовая комплектация NetStrazh 3.5

Проверяется неизменённый архив `Netstrazh 3.5.zip` из коммита
`dd365f008db6563365a72d5aa5448f72af001dd6`. Параметр `BUILD_NUMBER=35`
даёт `versionName=3.5`, `versionCode=135`, пакет `lab.netstrazh`.
Сборка отладочная; выпускной APK не собирался и его работа не проверялась.

## Результат сборки

- JDK 21.0.13; Gradle 8.11.1; compileSdk35, minSdk29, targetSdk34.
- `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`: **успех**,
  1м46с,75 задач. [Исходный лог](logs/build-original.log).
- **40/40 модульных тестов успешно**,0failures,0errors,0skipped:
  Socks12, DnsPipe8, Packet13, UpstreamDeadline3, UpstreamBoundary4.
  [Машинная сводка](logs/unit-test-summary.json), [XML результатов](unit-test-results).
- Lint: **0 ошибок,29 предупреждений**.21SetTextI18n,3GradleDependency,
  2StaticFieldLeak, по1OldTargetApi/DataExtractionRules/ObsoleteSdkInt.
  Это диагностические предупреждения, сами по себе не доказанные уязвимости.
  [Сводка](logs/lint-summary.json), [полный XML](lint-results-debug.xml).
- Дополнительные тесты из внешней папки подключены через
  [extra-tests.init.gradle](extra-tests.init.gradle), исходники приложения не редактировались.
  Первая сборка таких тестов успешна за31с; в логе были два неудачных запуска
  Kotlin compiler daemon, после чего сборка восстановилась сама.
  [Лог](logs/build-review-tests.log).
- После добавления AppListReviewTest и InsideRealV2rayTest выполнена окончательная
  сборка тестового APK: **успех**,22с,4задачи выполнено/57up-to-date.
  [Лог](logs/build-review-tests-final.log). Повторная сборка требовалась потому,
  что эти два файла появились после предыдущего APK.

APK: `build-output/app/outputs/apk/debug/app-debug.apk` и
`build-output/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
[Контрольные суммы](artifacts/built-apks.json).
Метаданные версии: [output-metadata.json](build-output/app/outputs/apk/debug/output-metadata.json).

## Дополнительные Android-классы

Компиляция не означает, что инструментальный тест прошёл. Фактические результаты
эмулятора приведены отдельно в итоговом отчёте и логах instrumentation.

- DomainRegressionTest, LocalRuntimeTest, IndependentClientTest,
  UnknownUidReviewTest, RealV2rayTest, SettledRealV2rayTest перенесены с2.0;
  файл отчёта независимого клиента переименован в `independent-client-v35.txt`.
- HostRulesReviewTest использует локальный HTTP Host, проверяет реальную блокировку,
  счётчик/время попытки, совместимость старого формата правил и разблокировку.
- **HostHistoryReviewTest — воспроизведение дефекта**, его PASS означает,
  что заново созданному правилу ошибочно приписана более старая запись журнала.
  Он вставляет одну синтетическую запись с уникальным именем и не ходит в сеть.
- AppListReviewTest меняет исключение другого пакета4раза, после каждого изменения
  ждёт свежую проверку захвата текущего движка, проверяет точный TCP/UDP обмен,
  учёт и надпись подтверждённой защиты. Не измеряет краткое окно до подтверждения.
- InsideRealV2rayTest —3сценария с настоящей v2rayNG2.3.11 внутри VPN:
  владелец слушающего порта, TCP,UDP, учёт обоих UID.
- LockdownRealV2rayTest — те же3сценария при заранее включённых штатными настройками
  Android Always-on VPN+Block connections without VPN. Приложению задано
  `bypassApp=true`; тест требует, чтобы фактического исключения прокси не было.
  Тест не включает и не выключает системные переключатели сам.

Профиль v2rayNG перенаправляет TEST-NET адрес на локальный ответчик.
Положительный результат этих классов не доказывает работу с удалённым
VLESS/VMess/Trojan сервером и не отдельно проверяет исключение по UID для
внешнего публичного адреса: конечный адрес самого прокси в стенде локальный.

## Сохранность исходников

**Все63 исходных файла архива имеют прежние SHA-256.**
Gradle/Kotlin создали два дополнительных служебных журнала ошибок внутри
`.gradle/kotlin/errors` и `.kotlin/errors`; ни один исходный файл не изменён.
Основные build/cache каталоги вынесены за пределы распакованного проекта.
[Проверка целостности](logs/source-integrity-build-final.json).


## Исправление тестового ответчика после первого Android-прогона

Первый HostRulesReviewTest использовал имя домена с точками как метку URL `/echo/...`.
Локальный HTTP-ответчик принимает только буквы, цифры и дефисы, поэтому после
снятия блокировки отвечал404, а не ожидаемым телом. Это ошибка проверочного теста,
не доказательство ошибки разблокировки в Страже.
Оригинал теста сохранён в [HostRulesReviewTest-original.kt](resource-audit/HostRulesReviewTest-original.kt).
Прямая проверка ответчика с одинаковым dotted Host: [host-fixture-marker-check.json](logs/host-fixture-marker-check.json)
— старая метка даёт404, исправленная200с точным телом.

В тесте метка теперь отделена от доменного имени для обеих фаз, а после снятия
блокировки дополнительно проверяются HTTP200, наличие разделителя заголовков
и точное тело ответа. Код приложения не менялся.
Пересборка тестового APK **успешна за21с**,4задачи/57up-to-date:
[build-review-tests-host-fixture-fix.log](logs/build-review-tests-host-fixture-fix.log).
После одного неудачного запуска Kotlin compiler daemon сборка восстановилась.
Текущие контрольные суммы APK обновлены в `artifacts/built-apks.json`,
предыдущие сохранены в `artifacts/built-apks-before-host-fixture-fix.json`.
[Повторная проверка63исходных файлов](logs/source-integrity-build-after-fixture-fix.json)
подтвердила неизменность всех оригинальных файлов.
Результат повторного Android-прогона следует читать отдельно; успешная пересборка
сама по себе не означает успешное исполнение теста.


## Дополнительная диагностика lockdown

После основных прогонов добавлены диагностика готовности и отдельный `LockdownColdStartReviewTest`. Обе сборки тестового APK успешны (22 и25с); приложение не изменено. Исходные неудачные прогоны сохранены. Контрольные суммы в `artifacts/built-apks-lockdown-diagnostics.json` и `artifacts/built-test-apk-cold-start.json`. Итоговая проверка всех63 исходных файлов: `logs/source-integrity-final.json`.
