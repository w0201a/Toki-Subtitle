#!/usr/bin/env python3
"""Make a new Kotlin-isolated DEX from a supplied LSPatch loader. Never edits/installs the APK."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile
import zlib

LOADER_ENTRY='assets/lspatch/loader.dex'

def uleb(data:bytes,offset:int)->tuple[int,int]:
    value=0
    for shift in range(0,35,7):
        if offset>=len(data):raise ValueError('Truncated ULEB128')
        n=data[offset];offset+=1;value|=(n&127)<<shift
        if n<128:return value,offset
    raise ValueError('Oversized ULEB128')

def utf16_key(raw:bytes)->tuple[int,...]:
    # DEX modified UTF-8 encodes UTF-16 surrogate code units and NUL specially.
    return tuple(map(ord,raw.replace(b'\xc0\x80',b'\x00').decode('utf-8','surrogatepass')))

def relocate(data:bytes)->tuple[bytes,dict]:
    if len(data)<112 or data[:4]!=b'dex\n' or data[7]!=0:
        raise ValueError('Not a standard DEX file')
    if struct.unpack_from('<I',data,32)[0]!=len(data):raise ValueError('DEX file size mismatch')
    if struct.unpack_from('<I',data,40)[0]!=0x12345678:raise ValueError('Unsupported DEX byte order')
    if data[12:32]!=hashlib.sha1(data[32:]).digest():raise ValueError('Original DEX SHA1 mismatch')
    if struct.unpack_from('<I',data,8)[0]!=zlib.adler32(data[12:])&0xffffffff:raise ValueError('Original DEX checksum mismatch')
    count,table=struct.unpack_from('<II',data,56)
    if table<112 or table+4*count>len(data):raise ValueError('Invalid string table')
    entries=[]
    for i in range(count):
        pos=struct.unpack_from('<I',data,table+4*i)[0]
        length,start=uleb(data,pos)
        try:end=data.index(0,start)
        except ValueError:raise ValueError('Unterminated DEX string')from None
        raw=data[start:end]
        if len(utf16_key(raw))!=length:raise ValueError('DEX UTF16 length mismatch')
        entries.append((start,end,raw))
    old=[x[2]for x in entries]
    if any(utf16_key(a)>=utf16_key(b)for a,b in zip(old,old[1:])):raise ValueError('Original strings are not strictly sorted')
    if b'Lkotlin/jvm/internal/Intrinsics;'not in old:raise ValueError('No bundled Kotlin Intrinsics to isolate')
    if any(s in (b'kotlim',b'kotlimx') or any(prefix in s for prefix in (b'kotlim/',b'kotlim.',b'kotlimx/',b'kotlimx.')) for s in old):raise ValueError('Relocation namespace already exists; refusing collision')
    new=[s.replace(b'kotlin',b'kotlim')for s in old]
    if any(utf16_key(a)>=utf16_key(b)for a,b in zip(new,new[1:])):raise ValueError('Relocation changes string order; full DEX rebuild required')
    output=bytearray(data)
    changed=0
    for (start,end,before),after in zip(entries,new):
        if len(before)!=len(after):raise AssertionError('Relocation must be same length')
        if before!=after:changed+=1;output[start:end]=after
    output[12:32]=hashlib.sha1(output[32:]).digest()
    struct.pack_into('<I',output,8,zlib.adler32(output[12:])&0xffffffff)
    return bytes(output),dict(stringCount=count,modifiedStrings=changed,sizePreserved=len(output)==len(data),stringOrderingPreserved=True,sourceSha256=hashlib.sha256(data).hexdigest(),outputSha256=hashlib.sha256(output).hexdigest(),boundary='DEX namespace relocation only; no manager signature, installation or device compatibility claim')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path,help='LSPatch manager APK or original loader.dex')
    parser.add_argument('--output',required=True,type=Path,help='New output DEX, must not exist')
    parser.add_argument('--report',type=Path,help='New JSON report, must not exist')
    args=parser.parse_args()
    if args.output.exists()or args.report and args.report.exists():raise FileExistsError('Refusing to overwrite an existing output')
    if zipfile.is_zipfile(args.source):
        with zipfile.ZipFile(args.source)as z:
            info=z.getinfo(LOADER_ENTRY)
            if info.file_size>16*1024*1024:raise ValueError('Loader exceeds bounded read size')
            source=z.read(info)
    else:source=args.source.read_bytes()
    result,report=relocate(source)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_bytes(result)
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        args.report.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
