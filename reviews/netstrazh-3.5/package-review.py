from pathlib import Path
import hashlib,shutil,zipfile,json,re
r=Path(__file__).resolve().parent
out=r/'publication'
assert not out.exists(), 'Use a fresh publication directory'
report=r/'NetStrazh-3.5-review.md'
assert report.exists() and not any(t in report.read_text() for t in ['[ROOT','[PENDING'])
out.mkdir()
names=['NetStrazh-3.5-review.md','build-audit.md','dns-audit.md','lifecycle-audit.md','general-audit.md','REPRODUCTION.md','source-manifest.json','review-input.json','run-gradle.sh','start-emulator.sh','build-outputs.init.gradle','extra-tests.init.gradle','run-android-test.py','local_test_servers.py','lint-results-debug.xml','lockdown-audit.md','lockdown-startup-audit.md','probe-client.py','ui_dump.py','package-review.py','summarize-android.py']
for directory in ['logs','unit-test-results','test-src','audit-dns','resource-audit','client-probe']:
 for p in (r/directory).rglob('*'):
  if p.is_file() and not p.is_symlink() and p.suffix in ['.md','.txt','.json','.log','.xml','.kt','.java','.py','.sh','.apk']:
   names.append(str(p.relative_to(r)))
for p in (r/'artifacts').iterdir():
 if p.is_file() and p.suffix in ['.txt','.json','.sha256']:
  names.append(str(p.relative_to(r)))
names.append('build-output/app/outputs/apk/debug/output-metadata.json')
for name in sorted(set(names)):
 src=r/name; dst=out/name; dst.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(src,dst)
shutil.copy2(report,out/'NetStrazh-3.5-review.txt')
(out/'README.md').write_text('''# Проверка NetStrazh 3.5

- **[Скачать отчёт, логи и тесты одним ZIP](NetStrazh-3.5-review-materials.zip?raw=true).**
- [Читать итоговый отчёт](NetStrazh-3.5-review.md) · [текстовая копия](NetStrazh-3.5-review.txt).
- [Логи](logs) · [Как повторить](REPRODUCTION.md).

Проверен [архив Netstrazh 3.5.zip](../../Netstrazh%203.5.zip) из коммита
`dd365f008db6563365a72d5aa5448f72af001dd6` ветки `Netstrazh`.
Распакованная папка исходников в этой ветке отличается от ZIP.
Отчёт от 15 ноября 2026. Приложение при проверке не изменялось; в этой папке только материалы аудита.

Первоначальные ошибки тестов сохранены вместе с отдельными проверками после
восстановления. Успех поздних запросов не отменяет сбой первых запросов после старта.
Смотрите итоговый отчёт для границ вывода; локальный стенд не проверяет личный
удалённый сервер v2rayNG и конкретный телефон.

Контрольные суммы файлов: [SHA256SUMS](SHA256SUMS).
Контрольная сумма архива: [archive-sha256.txt](archive-sha256.txt).
Пути `/workspace` в скриптах и сырых логах относятся к облачному стенду;
для другой машины их надо адаптировать. Ссылки внутри отчёта относительные.
''')
payload=sorted(p for p in out.rglob('*') if p.is_file())
(out/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+str(p.relative_to(out))+'\n' for p in payload))
archive=out/'NetStrazh-3.5-review-materials.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for p in sorted(out.rglob('*')):
  if p.is_file() and p!=archive: z.write(p,str(p.relative_to(out)))
with zipfile.ZipFile(archive) as z: assert z.testzip() is None
h=hashlib.sha256(archive.read_bytes()).hexdigest()
(out/'archive-sha256.txt').write_text(h+'  '+archive.name+'\n')
shutil.copy2(archive,r.parent/archive.name)
shutil.copy2(report,r.parent/report.name)
shutil.copy2(report,r.parent/'NetStrazh-3.5-review.txt')
print(json.dumps({'files':len(list(out.rglob('*'))),'zip_bytes':archive.stat().st_size,'sha256':h},indent=2))
