from pathlib import Path
import subprocess
import os
import tempfile
root=Path(__file__).resolve().parents[2]
check=Path(os.environ.get('WHITELIST_TEST_DEPS', str(Path(tempfile.gettempdir())/'sms-whitelist-test-deps')))
android=Path(os.environ['ANDROID_JAR'])
(check/'classes').mkdir(parents=True,exist_ok=True)
classpath=':'.join(map(str,list(check.glob('*.jar'))+[android]))
files=['BotWhitelistRequest','DingTalkWhitelistRequest','DingTalkStreamClient','WeComStreamClient','FeishuStreamClient','FeishuBotIdentity','FeishuMessageText','TelegramRemotePoller']
tests=['DingTalkWhitelistRequestTest','DingTalkWhitelistRoutingTest','BotWhitelistRoutingTest']
source=[str(root/'app/src/main/kotlin/org/fossify/messages/remote'/f'{name}.kt') for name in files]
source += [str(root/'app/src/test/kotlin/org/fossify/messages/remote'/f'{name}.kt') for name in tests]
source += [str(p) for p in (Path(__file__).resolve().parent/'stubs').glob('*.kt')]
source += [str(root/'app/src/main/kotlin/org/fossify/messages/remote/runtime/RemoteSourceRuntimeManager.kt')]
cmd=['java','-cp',str(check/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',classpath,'-d',str(check/'classes')]+source
subprocess.run(cmd,check=True)
subprocess.run(['java','-cp',str(check/'classes')+':'+str(check/'*')+':'+str(android),'org.junit.runner.JUnitCore']+['org.fossify.messages.remote.'+name for name in tests]+['org.fossify.messages.remote.BotWhitelistHostIntegrationTest'],check=True)
