from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

name = sys.argv[1]
if not name.replace('-', '').replace('_', '').isalnum():
    raise SystemExit('Invalid capture name')
adb = ['/workspace/android-sdk/platform-tools/adb', '-s', 'emulator-5554']
remote = '/data/local/tmp/' + name + '.xml'
local = Path('/workspace/netstrazh-v3.5-review/logs') / (name + '.xml')
subprocess.run(adb + ['shell', 'uiautomator', 'dump', remote], check=True, timeout=45, stdout=subprocess.DEVNULL)
subprocess.run(adb + ['pull', remote, str(local)], check=True, timeout=15, stdout=subprocess.DEVNULL)
for node in ET.parse(local).iter('node'):
    if node.get('text') or node.get('content-desc'):
        print(node.get('text'), '|', node.get('content-desc'), '|', node.get('bounds'), '| checked=' + node.get('checked', ''))
