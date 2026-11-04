from pathlib import Path
import subprocess,sys,time
r=Path('/workspace/netstrazh-v1.1.15-review')
adb='/workspace/android-sdk/platform-tools/adb'
for spec in sys.argv[1:]:
 cls,_,suffix=spec.partition(':')
 label=cls.split('.')[-1]+suffix
 cmd=[adb,'shell','am','instrument','-w','-r','-e','class',cls,'lab.netstrazh.test/androidx.test.runner.AndroidJUnitRunner']
 print('START',label,flush=True)
 with (r/'logs'/f'{label}.log').open('w') as out:
  try:
   p=subprocess.run(cmd,stdout=out,stderr=subprocess.STDOUT,timeout=600)
   status=str(p.returncode)
  except subprocess.TimeoutExpired:
   status='TIMEOUT'
 print('END',label,'process_exit',status,flush=True)
 text=(r/'logs'/f'{label}.log').read_text()
 print('\n'.join(text.splitlines()[-12:]),flush=True)
 subprocess.run([adb,'shell','logcat','-d','-s','NetStrazh:V','NetStrazhRuntimeTest:V','NetStrazhExternalTest:V','AndroidRuntime:E','Vpn:V'],stdout=(r/'logs'/f'{label}-logcat.txt').open('w'),timeout=30)
