# Материалы проверки NetStrazh 1.1.12

Проверялись неизменённые исходники архива `Netstrazh 1.1.12.zip` из
LabCodex, коммит `0246d6f00df47ac91daea35c6e9b68008071017f`.
SHA-256 архива: `500b953a7a3797b9ad14ac3085bbb9da8f09f72391127c206d5d5f54ad8dc84f`.
Это материалы проверки, а не исправленная сборка приложения.

Среда: Android 10/API 29 x86, SDK в `/workspace/android-sdk`, JDK 21,
Gradle 8.11.1. `BUILD_NUMBER=12` даёт versionName 1.1.12 / versionCode 112.
Тестовые исходники добавлялись только в androidTest через внешний init script.
Проверка не требует удалённых VPN-серверов или пользовательских секретов.

## Повторение в сохранённой облачной среде

1. Запустить `bash /workspace/netstrazh-v1.1.12-review/start-emulator.sh`.
   Дождаться загрузки Android и готовности PackageManager/ActivityManager.
2. Отдельным процессом запустить `python3 /workspace/netstrazh-tools/local_test_servers.py`.
   Он слушает loopback TCP 18080 и UDP 18081; эмулятор обращается к 10.0.2.2.
3. Собрать APK и тесты:

```sh
bash /workspace/netstrazh-v1.1.12-review/run-gradle.sh \
  -I /workspace/netstrazh-v1.1.12-review/extra-tests.init.gradle \
  assembleDebug assembleDebugAndroidTest
```

4. Установить APK из `build-output/app/outputs/apk/debug/` и
   `build-output/app/outputs/apk/androidTest/debug/` с `adb install --no-streaming -r`.
5. Для независимого приложения дополнительно установить
   `client-probe/review-client.apk`. Его исходники, сборочный скрипт и инструкция
   находятся в `client-probe`. Это отдельный тестовый пакет, без доступа к личным данным.
6. Выполнять тестовые классы последовательно, дожидаясь завершения каждого:

```sh
adb shell am instrument -w -r \
  -e class lab.netstrazh.DomainRegressionTest \
  lab.netstrazh.test/androidx.test.runner.AndroidJUnitRunner
```

Вместо DomainRegressionTest можно указать LocalRuntimeTest,
IndependentClientTest или RealV2rayTest. Тесты меняют VPN и правила выделенного
эмулятора; для телефона с рабочими настройками нужна отдельная подготовка.

## Что проверяют дополнительные классы

- DomainRegressionTest: t01 — разные имена в одном UDP-сокете; t02 —
  запрет имени через TCP DNS; t03/t04 — применение новой политики приложения/DNS
  к старым сессиям; t05 — несколько DNS-сообщений в одном TCP-соединении,
  отправляемых частями. Собственный DNS/SOCKS-ответчик не выходит в Интернет.
- LocalRuntimeTest: прямые TCP/UDP, блокировка/разблокировка, выключение/включение
  VPN. Одного ответа сервера недостаточно: проверяется свежий учёт UID и байтов.
- IndependentClientTest: отдельный пакет `lab.netstrazh.reviewclient` создаёт
  новые TCP/UDP-сокеты до выключения, при выключенном VPN и после включения.
  PID и идентификатор процесса должны сохраняться. Проверяются точные ответы,
  адреса сокетов, отсутствие явной привязки к сети, отдельный UID и свежий учёт.
  Отчёт записывается в файлы приложения `files/independent-client-v112.txt`.
- RealV2rayTest: настоящая отдельная v2rayNG 2.3.11 в режиме Proxy only.
  Импортировать `artifacts/v2rayNG-fixture-profile.json` и запустить сервис.
  Профиль маршрутизирует только 198.51.100.10:18080/TCP и :18081/UDP на локальные
  ответчики; другие назначения блокируются. Это не проверка удалённого
  VLESS/VMess/Trojan-сервера. Проверяются UID, маршрут, блокировка и восстановление.

Для результатов используйте итоговый отчёт и логи с суффиксом repeat вместе
с первыми запусками: первоначальные ошибки TCP не скрыты. Прежняя версия 1.1.10
не засчитывалась как успешная проверка текущей версии.

## Перенос на другую машину

Абсолютные пути в shell/init-скриптах и build.sh относятся к этому стенду.
Адаптируйте их под свою копию проекта и SDK. В архиве материалов находятся
новые тесты, исходники отдельного клиента и его тестовый APK, Python-ответчик,
контролируемый профиль v2rayNG и результаты. Исходный ключ подписи Стража,
его пароли, APK v2rayNG и личные конфигурации серверов в материалы не включены.
