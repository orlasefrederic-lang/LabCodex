# Повторение проверки NetStrazh 1.1.15

Вход: `Netstrazh 1.1.15.zip`, ветка `Netstrazh`, коммит `6ebf12104927ddbc4ee22ee126361476ea5646ad`.
SHA-256: `387a0d53d63310a113604f4000261e2aa9431803cae1ddf29369d9ae6a3fda66`.
Используйте архив: распакованная папка проекта в ветке содержит другую версию.
Это материалы проверки неизменённых исходников, а не исправленная сборка приложения.

## Сохранённый стенд

Android 10/API29 x86, JDK21.0.13, Gradle8.11.1, Android SDK35.
Все команды ниже относятся к `/workspace/netstrazh-v1.1.15-review`.
Абсолютные пути в скриптах привязаны к этой облачной среде. На другой машине
понадобятся Android SDK, JDK и Gradle, извлечение исходного ZIP и изменение путей.
Секреты и приватные серверы для тестов не нужны.

1. Запустить `bash start-emulator.sh` и дождаться `adb shell getprop sys.boot_completed` = `1`.
2. Запустить отдельно `python3 local_test_servers.py`: loopback TCP18080 и UDP18081.
3. Собрать приложение и тесты:

```sh
bash run-gradle.sh testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
bash run-gradle.sh -I extra-tests.init.gradle assembleDebug assembleDebugAndroidTest
```

`BUILD_NUMBER=15` выставлен скриптом и даёт versionName1.1.15/code115.
Дополнительные тесты добавляются внешним Gradle init script, исходники приложения не меняются.

4. Установить APK приложения из `build-output/app/outputs/apk/debug/`,
   тестовый APK из `build-output/app/outputs/apk/androidTest/debug/` и
   `client-probe/review-client.apk` через `adb install --no-streaming -r`.
5. Последовательно запускать классы:

```sh
python3 run-android-test.py lab.netstrazh.DomainRegressionTest lab.netstrazh.LocalRuntimeTest
python3 run-android-test.py lab.netstrazh.IndependentClientTest
```

Тесты меняют VPN и правила выделенного эмулятора; рабочий телефон потребует отдельной подготовки.
Android instrumentation может вернуть код процесса0 при провале теста: проверяйте строки
`OK (...)` / `FAILURES!!!`, а не только код shell.

## Что именно проверяют сценарии

- DomainRegressionTest:5 сценариев DNS UDP/TCP, домены в одном сокете, новая политика для старых потоков.
- LocalRuntimeTest:5 сценариев прямого TCP/UDP, запрет/разрешение и off/on с новым учётом.
- IndependentClientTest: отдельный UID10119; один процесс, новые TCP/UDP-сокеты до off/on,
  во время выключения и после каждого из3 включений. Точный payload, источник,
  UID, PID/nonce, отсутствие явной привязки к сети, записи и счётчики.
- Первая версия трёхциклового теста: `artifacts/IndependentClientTest-first-run.kt.txt`.
  Итоговый исходник дополнительно ждёт свежую успешную самопроверку VPN и выполняет
  новую пару запросов `settled-N`. Ошибки первых запросов сохраняются.
  Внутренний отчёт: `adb shell run-as lab.netstrazh cat files/independent-client-v115.txt`.
- RealV2rayTest: настоящая v2rayNG2.3.11 в Proxy only; владелец порта, TCP/UDP,
  LAN, правила, автообнаружение приложения прокси и пересоздание VPN.

- SettledRealV2rayTest повторяет7 сценариев RealV2rayTest, но после настройки/пересоздания
  VPN сначала ожидает свежую успешную самопроверку для текущего engine (до30с).
  Это отдельная диагностика восстановления; она не заменяет первые ошибки и
  не утверждает отсутствие окна без перехвата до проверки.

## Настоящая v2rayNG

Установить официальный x86 APK2.3.11 (контрольные данные в `artifacts/v2rayNG-artifact-verification.json`).
Импортировать `artifacts/v2rayNG-fixture-profile.json`, выбрать **Proxy only**, запустить сервис.
`adb shell dumpsys activity services com.v2ray.ang` должен показывать `CoreProxyOnlyService`.
Затем выполнить:

```sh
python3 run-android-test.py lab.netstrazh.RealV2rayTest
python3 run-android-test.py lab.netstrazh.SettledRealV2rayTest
```

Профиль разрешает только TEST-NET198.51.100.10 TCP18080/UDP18081 и перенаправляет
их на локальные ответчики10.0.2.2. Всё остальное блокируется. Это проверка реального
Xray/SOCKS, но не удалённого VLESS/VMess/Trojan-сервера.

## Отдельные JVM-проверки

`audit-dns/run-upstream-audit.py` и `run-slow-connect-audit.py` компилируют неизменённые
Upstream/DnsPipe/Socks из распакованного ZIP, подменяя только Android-границу стенда.
Первый показывает превышение общего DNS-бюджета, второй проверяет успешный CONNECT
с задержкой7.5с и точный обмен данными. Ответчики слушают loopback и не ходят наружу.

`resource-audit/DbQueueRaceModel.java` — модель точных операций очереди/счётчика
с заданным чередованием потоков. Это не запуск оригинального Android Db.

В материалах нет приватного ключа подписи Стража, личных VPN-конфигураций, SDK,
эмуляторного диска или исходного архива приложения. Клиентский тестовый APK подписан
одноразовым отладочным ключом; приватный ключ в материалы не включён.

## Служебные ошибки стенда

После длительной паузы эмулятор показал ANR системного SystemUI; выбрано Wait,
готовность интерфейса проверена до следующих тестов. В первом независимом прогоне
дополнительный `uiautomator dump` был ошибочно запущен параллельно instrumentation:
он завершился ошибкой регистрации UiAutomationService в собственном процессе.
Сами instrumentation-проверки завершились; ошибка вспомогательного инструмента
не записана как падение NetStrazh. Последующие UI-проверки выполнялись отдельно.
