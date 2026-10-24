"""Compile the production UI repair worker with simulated Android/WorkManager/Room boundaries.
Set SMS_UI_TEST_DEPS to a directory containing Kotlin compiler/runtime JARs.
This harness does not connect to a phone, read real SMS, or send messages.
"""
from pathlib import Path
import os, subprocess, tempfile, urllib.request
root=Path(__file__).resolve().parents[2]
base=Path(os.environ.get('SMS_UI_TEST_DEPS',Path(tempfile.gettempdir())/'sms-ui-test-deps'))
base.mkdir(parents=True,exist_ok=True)
if 'SMS_UI_TEST_DEPS' not in os.environ:
    deps={
      'org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.10':'kotlin-compiler-embeddable-2.4.10.jar',
      'org/jetbrains/kotlin/kotlin-stdlib/2.4.10':'kotlin-stdlib-2.4.10.jar',
      'org/jetbrains/kotlin/kotlin-script-runtime/2.4.10':'kotlin-script-runtime-2.4.10.jar',
      'org/jetbrains/kotlin/kotlin-reflect/1.6.10':'kotlin-reflect-1.6.10.jar',
      'org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0':'kotlinx-coroutines-core-jvm-1.8.0.jar',
      'org/jetbrains/annotations/13.0':'annotations-13.0.jar',
    }
    for path,name in deps.items():
        target=base/name
        if not target.exists():
            with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+path+'/'+name,timeout=90) as response:
                data=response.read()
            target.write_bytes(data)
cp=os.pathsep.join(str(x) for x in base.glob('*.jar'))
files=list((Path(__file__).parent/'stubs').glob('*.kt'))+[root/'app/src/main/kotlin/org/fossify/messages/messaging/IncomingSmsUiSyncWorker.kt']
with tempfile.TemporaryDirectory() as classes:
    subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-jvm-target','17','-classpath',cp,'-d',classes,*map(str,files)],check=True)
    subprocess.run(['java','-cp',classes+os.pathsep+cp,'check.RunKt'],check=True)
