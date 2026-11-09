from pathlib import Path
import subprocess
base=Path('/workspace/netstrazh-tools/gradle-home/caches/modules-2/files-2.1')
root=Path('/workspace/netstrazh-v2.0-review'); audit=root/'audit-dns'
src=root/'sources/Lab-claude-confident-cray-n5kkx8/NetStrazh/app/src/main/java/lab/netstrazh'
versions=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21'),('org.jetbrains.kotlin','kotlin-stdlib','2.0.21'),('org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),('org.jetbrains','annotations','13.0'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4')]
jars=[next((base/g/a/v).glob('*/*.jar')) for g,a,v in versions]
java='/workspace/netstrazh-tools/jdk-21.0.13/bin/java'; cp=':'.join(map(str,jars)); classes=audit/'upstream-classes'
cmd=[java,'-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(map(str,[jars[1],jars[5]])),'-d',str(classes),str(src/'net/Socks.kt'),str(src/'vpn/DnsPipe.kt'),str(src/'vpn/Upstream.kt'),str(audit/'AndroidConstantsStub.kt'),str(audit/'EngineStubs.kt'),str(audit/'UpstreamDeadlineAudit.kt')]
subprocess.run(cmd,check=True,timeout=60)
r=subprocess.run([java,'-cp',':'.join(map(str,[classes,jars[1],jars[5]])),'UpstreamDeadlineAuditKt'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=25)
(audit/'upstream-deadline-result.txt').write_text(r.stdout); print(r.stdout,end=''); raise SystemExit(r.returncode)
