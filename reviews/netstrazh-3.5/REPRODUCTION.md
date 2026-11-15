# Повторение проверки NetStrazh 3.5

Вход: `Netstrazh 3.5.zip`, ветка `Netstrazh`, коммит `dd365f008db6563365a72d5aa5448f72af001dd6`.
SHA-256: `74df510ee434a54ad6bb04db054d565d283c6d673e259afa9df3e9115663b25f`.
Используйте этот архив: отдельная папка проекта в ветке содержит другой набор исходников.
Проверка выполняется без правки исходных файлов приложения; внешние тесты и сборочные каталоги находятся рядом.

Этот документ описывает подготовку и команды. Фактические результаты и ограничения конкретных запусков находятся в итоговом отчёте и логах, включая неудачные первые попытки, если они были. Наличие команды здесь не означает, что её запуск завершился успешно.

## Сохранённый стенд

Android 10/API 29 x86, JDK 21.0.13, Gradle 8.11.1, Android SDK35; программная эмуляция без KVM.
Все относительные пути ниже относятся к `/workspace/netstrazh-v3.5-review`.
Абсолютные пути в скриптах привязаны к облачной среде. На другой машине потребуются SDK/JDK/Gradle, исходный ZIP и изменение путей. Скрипт `run-gradle.sh` использует `/workspace/netstrazh-tools/build-env.sh` для имеющейся настройки среды и доверенных сертификатов; его содержимое с настройками среды не включается в публичные материалы.

1. Запустить `bash start-emulator.sh`; дождаться `adb shell getprop sys.boot_completed` = `1` и готового интерфейса.
2. Отдельно запустить `python3 local_test_servers.py`: loopback TCP18080 и UDP18081.
3. Собрать исходные тесты, приложение и lint:

```sh
bash run-gradle.sh testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

Исходные 40 unit-тестов находятся в пяти классах: `SocksTest`, `DnsPipeTest`, `PacketTest`, `UpstreamDeadlineTest`, `UpstreamBoundaryTest`. Для проверки использован `BUILD_NUMBER=35`: versionName3.5, versionCode135. Это номер проверочной сборки, а не доказательство номера пользовательского APK.

4. Добавить внешние Android-тесты через Gradle init script:

```sh
bash run-gradle.sh -I extra-tests.init.gradle assembleDebug assembleDebugAndroidTest
```

5. Установить `build-output/app/outputs/apk/debug/app-debug.apk`, `build-output/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk` и `client-probe/review-client.apk` через `adb install --no-streaming -r`.
6. Запускать классы последовательно, без одновременного `uiautomator dump`:

```sh
python3 run-android-test.py lab.netstrazh.DomainRegressionTest lab.netstrazh.LocalRuntimeTest
python3 run-android-test.py lab.netstrazh.IndependentClientTest lab.netstrazh.UnknownUidReviewTest
python3 run-android-test.py lab.netstrazh.HostRulesReviewTest lab.netstrazh.HostHistoryReviewTest lab.netstrazh.AppListReviewTest
```

Эти команды меняют правила и VPN выделенного эмулятора; рабочий телефон для них не используется. Android instrumentation может вернуть код процесса 0 при провале JUnit: проверять `OK (...)`, `FAILURES!!!` и исключения, а не только shell-код. `run-android-test.py` сохраняет вывод в `logs/<Class>.log` и отдельный logcat. Суффикс после двоеточия позволяет сохранить диагностический повтор отдельно, например `lab.netstrazh.DomainRegressionTest#имяМетода:-repeat`.

## Что проверяют внешние Android-классы

| Класс | Область |
|---|---|
| `DomainRegressionTest` | Пять сценариев DNS UDP/TCP, несколько имён на одном сокете, применение новой политики к старому потоку. DNS-ответы создаёт локальный тестовый SOCKS-сервер без внешней сети |
| `LocalRuntimeTest` | Локальные TCP/UDP, учёт UID и байтов, запрет/разрешение, выключение/включение из UI с проверкой нового захвата |
| `IndependentClientTest` | Три цикла off/on для отдельного APK/UID в одном процессе; свежие сокеты, точный payload, источник TUN/физической сети, PID/nonce, отсутствие явной привязки к сети, учёт и свежая проверка текущего engine |
| `UnknownUidReviewTest` | Явные правила `uid:-1`, разделение доменных правил, локальный трафик известного UID и открытие карточки. Реальный сбой Android owner lookup не создаётся |
| `HostRulesReviewTest` | Новый список адресов, сохранение старого boolean-формата правил, реальная локальная HTTP Host-блокировка, число попыток, экраны, снятие запрета с успешным контрольным payload |
| `HostHistoryReviewTest` | Диагностическое воспроизведение неверного исторического счётчика после удаления/создания правила. Один синтетический журналируемый сеанс; сеть не используется. **PASS означает, что дефект воспроизведён** |
| `AppListReviewTest` | Изменение списка приложений «мимо Стража», восстановление захвата и уведомления, новые локальные TCP/UDP после подтверждения текущего engine |
| `RealV2rayTest` | Семь сценариев настоящей v2rayNG: владелец, TCP/UDP, LAN, правила, автообнаружение и пересоздание VPN; без дополнительного ожидания capture watchdog |
| `SettledRealV2rayTest` | Те же семь сценариев с ожиданием свежей успешной проверки именно текущего engine. Отдельная диагностика устойчивого состояния, не отменяет ошибки первого варианта |
| `InsideRealV2rayTest` | Три сценария с включённым в VPN приложением v2rayNG: UID владельца, TCP, UDP; точный payload и записи обоих UID |
| `LockdownRealV2rayTest` | Те же три сценария при заранее включённой системной блокировке без VPN. В настройках прокси `bypassApp=true`, но фактическое исключение должно быть снято автоматически |
| `LockdownColdStartReviewTest` | Холодный запуск при включённом lockdown: подтверждение неверного автоматического исключения прокси, ограниченный HTTP-запрос, существующий `recheck()` и точные TCP/UDP с учётом обоих UID. **PASS означает воспроизведение дефекта и восстановление**, не отсутствие дефекта |

Отчёт независимого клиента сохраняется в `lab.netstrazh/files/independent-client-v35.txt`; прочитать через `adb shell run-as lab.netstrazh cat files/independent-client-v35.txt`. Диагностический JSON исторического счётчика — `files/host-history-review.json`; холодного запуска — `files/lockdown-cold-start-review.json`. При провале готовности `LockdownRealV2rayTest` диагностическая версия также сохраняет `files/lockdown-readiness-diagnostic.txt`. Эти артефакты нужно извлечь после соответствующего запуска.

Оригинальный `E2eTest` в ZIP содержит публичные сетевые зависимости (`example.com`, Google, внешние DNS). Локальные замены не объявляются полным успешным запуском оригинального E2E на интернете. В частности, оригинальный t23 использует внешний адрес Google для TLS, а локальный HostRulesReviewTest проверяет HTTP Host на контролируемом ответчике.

## Настоящая v2rayNG и Android lockdown

Используется официальный x86 APK v2rayNG2.3.11. Метаданные идентичности в `artifacts/v2rayNG-artifact-verification.json` перенесены из предыдущей проверки; это не повторная независимая проверка подписи релиза. Установленная версия и режим проверяются в текущем запуске.

Импортировать `artifacts/v2rayNG-fixture-profile.json`, выбрать Proxy only и запустить сервис. `adb shell dumpsys activity services com.v2ray.ang` должен показывать `CoreProxyOnlyService`, а не VPN-службу. Профиль принимает SOCKS на127.0.0.1:10808. Только TEST-NET198.51.100.10 TCP18080/UDP18081 перенаправляются на локальные ответчики10.0.2.2; остальные назначения запрещены.

```sh
python3 run-android-test.py lab.netstrazh.RealV2rayTest lab.netstrazh.SettledRealV2rayTest
python3 run-android-test.py lab.netstrazh.InsideRealV2rayTest
```

Для `LockdownRealV2rayTest` сначала открыть штатные Настройки Android → VPN → шестерёнка NetStrazh, включить «Постоянная VPN» и «Блокировать соединения без VPN». Затем:

```sh
python3 run-android-test.py lab.netstrazh.LockdownRealV2rayTest
python3 run-android-test.py lab.netstrazh.LockdownColdStartReviewTest
```

В проверке 3.5 `LockdownRealV2rayTest` дважды завершился ошибкой подготовки: 0 выполненных сценариев, 1 ошибка BeforeClass. Это не три проваленных payload-проверки. В повторе добавлены только снимки состояния каждого условия; таймаут и требования готовности не ослаблены. Исходная версия внешнего теста сохранена в `resource-audit/InsideRealV2rayTest-before-diagnostics.kt`, диагностический APK описан в `artifacts/built-apks-lockdown-diagnostics.json`; последующий класс холодного запуска добавляется отдельной сборкой из `logs/build-lockdown-cold-start-review.log`. Эти метаданные описывают разные этапы проверочного APK, а не изменение исходников приложения.

`LockdownColdStartReviewTest` затем прошёл 1/1: воспроизведено неправильное исключение и пустой HTTP-ответ, после существующего `recheck()` получены точные TCP/UDP-ответы и записи обоих UID. Системный lockdown не выключался. Для отдельного повторения убедиться, что v2rayNG снова запущена в Proxy only, а оба системных переключателя по-прежнему включены. Подробнее: [дефект запуска и фактическое доказательство](lockdown-startup-audit.md).

Зафиксировать исходные значения и восстановить системные переключатели после теста. Подготовка теста не должна незаметно изменять реальную системную блокировку; этот класс сам её не включает. Дополнительный `probe-client.py <метка>` запускает отдельный клиент и сохраняет `logs/lockdown-<метка>.json` с уникальным маркером; он пригоден для фаз работающий VPN / остановленный VPN под блокировкой / снятая блокировка.

В локальном профиле исходящие соединения v2rayNG идут на LAN-адрес10.0.2.2, который приложение и так пускает напрямую. Поэтому включённый-прокси тест подтверждает совместимость и захват в этой топологии, но сам по себе не доказывает отсутствие циклов для публичного IP удалённого сервера. Здесь не проверяется личный VLESS/VMess/Trojan-сервер.

## Независимые JVM-проверки

```sh
python3 audit-dns/run-upstream-audit.py
python3 audit-dns/run-boundary-audit.py
python3 audit-dns/run-slow-connect-audit.py
```

Скрипты компилируют неизменённые `Upstream.kt`, `DnsPipe.kt`, `Socks.kt` из `sources/…/NetStrazh/app/src/main/java/lab/netstrazh/` с локальными `audit-dns/AndroidConstantsStub.kt` и `EngineStubs.kt`. Для Kotlin-компилятора используются JAR из `/workspace/netstrazh-tools/gradle-home/caches/modules-2/files-2.1`. Результаты:

- `audit-dns/upstream-deadline-result.txt`: общий бюджет SOCKS greeting/CONNECT и DNS pipeline/fallback.
- `audit-dns/dns-boundary-result.txt`: искусственно медленные resolver и protect; дедлайн вызывающего запроса и закрытие созданных сокетов.
- `audit-dns/slow-connect-result.txt`: ответ CONNECT через7.5с после быстрого greeting; точный payload, отсутствие ложного «прокси завис».

Классы компиляции (`audit-dns/*-classes`) не нужны в публичном наборе: создаются скриптами заново. Подробные цифры и границы вывода — в `dns-audit.md`.

В публичные материалы не включаются приватный ключ подписи, личные VPN-конфигурации, SDK, диск эмулятора или исходный ZIP приложения. Тестовый клиентский APK использует отладочную подпись; приватного ключа в наборе нет.
