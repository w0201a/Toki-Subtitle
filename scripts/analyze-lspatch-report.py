#!/usr/bin/env python3
"""Read-only, redacted LSPatch ZIP triage. Never extracts files or prints raw logs/secrets."""
from __future__ import annotations
import argparse
import collections
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

MAX_ENTRY = 16 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
COLLISION = 'No static method checkNotNull(Ljava/lang/Object;)V'

def analyze(source: str | Path | io.BytesIO) -> dict:
    result = {
        'schema': 1,
        'frameworkKotlinCollision': False,
        'newVerboseFailureEvents': 0,
        'historicalFrameworkFailureEvents': 0,
        'historicalTemplateStartupLines': 0,
        'failureStages': [],
        'module139EntryConfirmed': False,
        'module139ApkTransferOnly': False,
        'shizukuGranted': None,
        'binderAvailable': None,
        'boundary': 'A transferred APK or manager binding is not proof of target module execution. No install, patch, reset or network change is performed.',
    }
    stages = collections.Counter()
    seen = set()
    total = 0
    with zipfile.ZipFile(source) as archive:
        for info in archive.infolist():
            relevant = info.filename in ('device.txt', 'shizuku.txt') or info.filename.startswith('logs/')
            if not relevant or info.is_dir():
                continue
            if info.file_size > MAX_ENTRY:
                raise ValueError('Report entry exceeds read limit')
            total += info.file_size
            if total > MAX_TOTAL:
                raise ValueError('Report exceeds total read limit')
            lines = archive.read(info).decode('utf-8', 'replace').splitlines()
            if info.filename == 'device.txt':
                text='\n'.join(lines)
                for key, pattern in [('androidApi',r'Android:.*API (\d+)'),('lspatch',r'LSPatch:\s*([^\r\n]+)'),('vector',r'Vector:\s*([^\r\n]+)')]:
                    match=re.search(pattern,text)
                    if match:result[key]=int(match[1])if key=='androidApi'else match[1]
                continue
            if info.filename == 'shizuku.txt':
                for key,label in [('shizukuGranted','Granted'),('binderAvailable','Binder available')]:
                    match=re.search(r'^'+label+r':\s*(true|false)$','\n'.join(lines),re.M)
                    if match:result[key]=match[1]=='true'
                continue
            for i,line in enumerate(lines):
                if COLLISION in line:
                    # Only call this the Vector bootstrap failure when its stack proves ownership.
                    context='\n'.join(lines[i:i+15])
                    if 'VectorInlinedCallers' in context and 'LSPApplication.onLoad' in context:
                        result['frameworkKotlinCollision']=True
                        verbose='/verbose_'in info.filename
                        timestamp=re.match(r'(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)',line)
                        event=(verbose,timestamp[1]if timestamp else info.filename+':'+str(i))
                        if event not in seen:
                            seen.add(event)
                            result['newVerboseFailureEvents'if verbose else 'historicalFrameworkFailureEvents']+=1
                        stages['Vector bootstrap before module entry']+=1
                if 'Unable to create application com.ss' in line and 'TemplateCleanupHook'in line:
                    result['historicalTemplateStartupLines']+=1
                if re.search(r'(?:moduleVersion(?:Code)?=139|moduleVersion=2\.1\.19)',line):
                    result['module139EntryConfirmed']=True
                if 'Toki-2.1.19-debug.apk'in line:
                    result['module139ApkTransferOnly']=True
    result['failureStages']=list(stages)
    if result['frameworkKotlinCollision']:
        result['requiredRepair']='Isolate the LSPatch/Vector framework classloader from the host Kotlin runtime, or repackage the framework runtime. A Toki-only update cannot run before this bootstrap failure.'
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report',type=Path)
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    result=analyze(args.report)
    result['reportSha256']=hashlib.sha256(args.report.read_bytes()).hexdigest()
    text=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output:
        if args.output.exists():
            raise FileExistsError('Refusing to overwrite an existing diagnosis; choose a new output path')
        args.output.write_text(text,encoding='utf-8')
    print(text,end='')

if __name__=='__main__':
    main()
