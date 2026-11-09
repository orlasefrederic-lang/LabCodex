from pathlib import Path
import subprocess,sys,time,json
r=Path('/workspace/netstrazh-v2.0-review'); adb='/workspace/android-sdk/platform-tools/adb'; pkg='lab.netstrazh.reviewclient'
label=sys.argv[1]
assert label.replace('-','').isalnum()
marker='lockdown-'+label+'-'+str(time.time_ns())
launch=subprocess.run([adb,'shell','am','start','-W','-n',pkg+'/.ProbeActivity','--es','marker',marker],capture_output=True,text=True,timeout=35)
(r/'logs'/f'lockdown-{label}-launch.txt').write_text(launch.stdout+launch.stderr)
end=time.monotonic()+65
while time.monotonic()<end:
 try:
  data=json.loads(subprocess.check_output([adb,'shell','run-as',pkg,'cat','files/result.json'],text=True,stderr=subprocess.DEVNULL,timeout=10))
  if data.get('marker')==marker:
   (r/'logs'/f'lockdown-{label}.json').write_text(json.dumps(data,indent=2)+'\n')
   print(json.dumps({k:data.get(k) for k in ['marker','uid','pid','networkBefore','tcp','udp','networkAfter']},indent=2));break
 except (subprocess.SubprocessError,json.JSONDecodeError): pass
 time.sleep(.5)
else: raise SystemExit('Helper timed out; incomplete probe, not a blocking result')
