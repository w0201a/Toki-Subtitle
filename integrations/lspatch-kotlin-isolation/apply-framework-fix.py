#!/usr/bin/env python3
"""Apply the pinned reusable LSPatch build fix to a CLEAN checkout. No APK or device modification."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

HERE=Path(__file__).resolve().parent

def run(args,cwd):
    return subprocess.run(args,cwd=cwd,check=True,capture_output=True,text=True,encoding='utf-8').stdout.strip()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('checkout',type=Path)
    parser.add_argument('--native-manager',type=Path,help='Optional exact487 binary used only for native assets')
    args=parser.parse_args();root=args.checkout.resolve();lock=json.loads((HERE/'source-lock.json').read_text())
    if run(['git','rev-parse','HEAD'],root)!=lock['frameworkCommit']:raise ValueError('Framework checkout differs from source-lock.json')
    if run(['git','rev-parse','HEAD'],root/'core')!=lock['vectorCommit']:raise ValueError('Vector checkout differs from source-lock.json')
    if run(['git','status','--porcelain','--untracked-files=no'],root):raise ValueError('Refusing to modify a dirty framework checkout')
    target=root/'tools/IsolateKotlinDex.java'
    if target.exists():raise FileExistsError('Existing build tool is not overwritten')
    if args.native_manager:
        if hashlib.file_digest(args.native_manager.open('rb'),'sha256').hexdigest()!=lock['binaryNativeReuse']['managerSha256']:
            raise ValueError('Prebuilt native manager must match the reviewed 487 artifact')
    patch=HERE/'framework-build.patch';run(['git','apply','--unidiff-zero','--check',str(patch)],root);run(['git','apply','--unidiff-zero',str(patch)],root)
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(HERE/'IsolateKotlinDex.java',target)
    print('Applied build-time Kotlin isolation. Both manager and CLI consume the verified output; no target app offsets are involved.')
    print('Build tasks: :manager:assembleDebug :jar:buildDebug')
    print('Test package: '+lock['testApplicationId']+' (coexists with original manager; no uninstall)')

if __name__=='__main__':main()
