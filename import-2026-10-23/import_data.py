import json,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
root=Path.cwd(); dest=root/'hm-video/import-2026-10-23'
adb=str(root/'tools/sdk/platform-tools/adb.exe');target='192.168.0.54:5555';pkg='com.hm.video.letv'
def run(*args):
 return subprocess.run([adb,'-s',target,*args],check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE).stdout
source=json.loads(Path(r'C:/Users/sz_jm/Desktop/hm_video_data_20261023.json').read_text(encoding='utf-8-sig'))
for key in ('favorites','history'):
 assert isinstance(source[key],list)
 for item in source[key]:
  assert isinstance(item,dict) and isinstance(item.get('url'),str) and item['url'].startswith('/')
  assert isinstance(item.get('title'),str)
run('shell','am','force-stop',pkg)
raw=run('shell','run-as',pkg,'cat','shared_prefs/hm_video_user_data.xml')
backup=dest/'before-import.xml'
if backup.exists():
 import time
 backup=dest/f'before-import-{time.time_ns()}.xml'
backup.write_bytes(raw)
xml=ET.fromstring(raw)
report={}
for source_key,pref_key,time_key in [('favorites','favorites_list','addedAt'),('history','history_list','playedAt')]:
 node=next((e for e in xml if e.attrib.get('name')==pref_key),None)
 old=json.loads(node.text or '[]') if node is not None else []
 merged={}
 for item in old+source[source_key]:
  prev=merged.get(item['url'])
  if prev is None or item.get(time_key,0)>prev.get(time_key,0):merged[item['url']]=item
 items=sorted(merged.values(),key=lambda x:x.get(time_key,0),reverse=True)
 if node is None:node=ET.SubElement(xml,'string',{'name':pref_key})
 node.text=json.dumps(items,ensure_ascii=False,separators=(',',':'))
 report[source_key]={'before':len(old),'incoming':len(source[source_key]),'after':len(items)}
output=dest/'merged-user-data.xml';ET.ElementTree(xml).write(output,encoding='utf-8',xml_declaration=True)
remote='/data/local/tmp/hm-video-import-20261023.xml'
run('push',str(output),remote)
run('shell','run-as',pkg,'cp',remote,'shared_prefs/hm_video_user_data.xml')
actual=run('shell','run-as',pkg,'cat','shared_prefs/hm_video_user_data.xml')
assert {e.attrib['name']: e.text for e in ET.fromstring(actual)} == {e.attrib['name']: e.text for e in xml}, 'Readback mismatch'
run('shell','rm',remote)
(dest/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report))
run('shell','am','start','-n',pkg+'/com.hm.video.ui.MainActivity')
