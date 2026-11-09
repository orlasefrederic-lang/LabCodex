# Повторение проверки NetStrazh 2.0

Вход: `Netstrazh ver 2.0.zip`, ветка `Netstrazh`, коммит `5edab39c4301f45940155552a7e6dca868c63541`.
SHA-256: `babd3eeb90b8c3cdb3f7c558641c720851c2ddea72f92b9dd2556631f5344883`.
Используйте архив: распакованная папка проекта в ветке содержит другую версию.
Это материалы проверки неизменённых исходников, а не исправленная сборка приложения.

## Сохранённый стенд

Android 10/API 29 x86, JDK 21.0.13, Gradle 8.11.1, Android SDK35.
Все команды ниже относятся к `/workspace/netstrazh-v2.0-review`.
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

`VERSION` задаёт versionName2.0; в проверочной сборке `BUILD_NUMBER=20` даёт code120.
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
Android instrumentation может вернуть код процесса 0 при провале теста: проверяйте строки
`OK (...)` / `FAILURES!!!`, а не только код shell.

## Что именно проверяют сценарии

- DomainRegressionTest:5 сценариев DNS UDP/TCP, домены в одном сокете, новая политика для старых потоков.
- LocalRuntimeTest:5 сценариев прямого TCP/UDP, запрет/разрешение и off/on с новым учётом.
- IndependentClientTest: отдельный UID 10119; один процесс, новые TCP/UDP-сокеты до off/on,
  во время выключения и после каждого из 3 включений. Точный payload, источник,
  UID, PID/nonce, отсутствие явной привязки к сети, записи и счётчики.
- IndependentClientTest содержит 3 цикла с первыми запросами и новыми запросами после
  свежей успешной самопроверки именно текущего движка. Его итоговый внутренний отчёт:
  `adb shell run-as lab.netstrazh cat files/independent-client-v20.txt`.
  Ожидание усилено по сравнению с 1.1.15: сравнивается текущий Live.startedAt, а не
  сохранённая до возможного пересоздания метка.
- UnknownUidReviewTest: локальная замена оригинального E2eTest.t18 (тот обращается
  к example.com). Явная политика uid:-1, сохранение локальногоTCP/UDP/учёта известного
  UID, открытие карточки и разделение доменных правил. Реальная ошибка определения
  владельца сокета не симулируется.
- RealV2rayTest: настоящая v2rayNG2.3.11 в Proxy only; владелец порта, TCP/UDP,
  LAN, правила, автообнаружение приложения прокси и пересоздание VPN.

- SettledRealV2rayTest повторяет 7 сценариев RealV2rayTest, но после настройки/пересоздания
  VPN сначала ожидает свежую успешную самопроверку для текущего engine (до 30 с).
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
их на локальные ответчики 10.0.2.2. Всё остальное блокируется. Это проверка реального
Xray/SOCKS, но не удалённого VLESS/VMess/Trojan-сервера.

## Отдельные JVM-проверки

`audit-dns/run-upstream-audit.py` и `run-slow-connect-audit.py` компилируют неизменённые
Upstream/DnsPipe/Socks из распакованного ZIP, подменяя только Android-границу стенда.
Первый повторяет старый сценарий общего DNS-бюджета, второй проверяет успешный
CONNECT с задержкой 7.5 с и точный обмен данными. Новый `run-boundary-audit.py` показывает
остаток с синхронным resolve и незакрытым каналом при истечении срока. Задержка
Android-границы в нём задана заглушкой; это не измерение настоящего Android resolver.
Ответчики слушают loopback и не ходят наружу.

В материалах нет приватного ключа подписи Стража, личных VPN-конфигураций, SDK,
эмуляторного диска или исходного архива приложения. Клиентский тестовый APK подписан
одноразовым отладочным ключом; приватный ключ в материалы не включён.

## Служебные особенности стенда

После длительной паузы эмулятор показал ANR системного SystemUI; выбрано Wait,
готовность интерфейса проверена до instrumentation. UI dump не запускался одновременно
с instrumentation. Длительное выполнение в программном эмуляторе не является замером
производительности телефона. Один DNS-сценарий в первом прогоне не уложился в
внутренний 2-секундный запас стенда перед запросом по старому потоку; правила и свежий контроль к этому времени уже применились; он повторён
отдельно без изменения теста, успешно. Оба лога сохранены.

## Системная блокировка без VPN

Отдельная ручная проверка выполнена после instrumentation через штатные настройки
Android, без скрытого API. Три фазы и границы описаны в [lockdown-audit.md](lockdown-audit.md).
`probe-client.py` запускает отдельный клиент и сохраняет ответ с уникальной меткой.
Оба переключателя и исходные значения настроек после теста восстановлены.
