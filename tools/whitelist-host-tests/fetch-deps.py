from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import urllib.request
import os
import tempfile
root=Path(os.environ.get('WHITELIST_TEST_DEPS', str(Path(tempfile.gettempdir())/'sms-whitelist-test-deps')));root.mkdir(exist_ok=True)
items=[('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.0'),('org.jetbrains.kotlin','kotlin-stdlib','2.2.0'),('org.jetbrains.kotlin','kotlin-script-runtime','2.2.0'),('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.2.0'),('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),('org.jetbrains','annotations','13.0'),('com.squareup.okhttp3','okhttp','4.12.0'),('com.squareup.okio','okio-jvm','3.6.0'),('org.json','json','20240303'),('junit','junit','4.13.2'),('org.hamcrest','hamcrest-core','1.3'),('com.larksuite.oapi','oapi-sdk','2.8.5')]
def fetch(item):
 group,name,version=item;file=root/(name+'-'+version+'.jar')
 if not file.exists():
  url='https://repo.maven.apache.org/maven2/'+group.replace('.','/')+'/'+name+'/'+version+'/'+file.name
  with urllib.request.urlopen(url,timeout=45) as r,file.open('wb') as f:
   while chunk:=r.read(1024*1024):f.write(chunk)
 return file.name
with ThreadPoolExecutor(max_workers=6) as pool:
 for result in pool.map(fetch,items):print(result,flush=True)
