from pathlib import Path
import subprocess,os,zipfile,json,hashlib
import argparse
parser=argparse.ArgumentParser(description='Create independent generic regression APKs without modifying TikTok or installing anything.')
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--jdk',type=Path,required=True)
parser.add_argument('--sdk',type=Path,required=True)
a=parser.parse_args()
w=a.output.resolve();w.mkdir(parents=True,exist_ok=True);tests=w/'fixtures';tests.mkdir(exist_ok=True)
jdk=a.jdk.resolve()/'bin';sdk=a.sdk.resolve();bt=next(p for p in sorted((sdk/'build-tools').iterdir(),reverse=True) if (p/'aapt2.exe').is_file());android=next((sdk/'platforms').glob('*/android.jar'));assert android.is_file()
env=os.environ.copy();env['JAVA_HOME']=str(jdk.parent);env['PATH']=str(jdk)+';'+env.get('PATH','')
def run(args):
 p=subprocess.run([str(x)for x in args],capture_output=True,text=True,encoding='utf-8',errors='replace',env=env)
 if p.returncode:raise RuntimeError(p.stdout+p.stderr)
 return p.stdout
common='''package io.github.meiyongai.toki.compatfixture;
public final class FixtureActivity extends android.app.Activity {
 protected void onCreate(android.os.Bundle saved){super.onCreate(saved);boolean entry="loaded".equals(System.getProperty("toki.compat.module"));String result="TOKI_COMPAT_CHECK package="+getPackageName()+" module="+entry;
 try{Class.forName("kotlin.jvm.internal.Intrinsics",false,getClassLoader()).getDeclaredMethod("checkNotNull",Object.class);result+=" oldHost=false";}catch(Throwable expected){result+=" oldHost=true";}
 android.widget.TextView label=new android.widget.TextView(this);label.setText(result);label.setTextSize(20);setContentView(label);android.util.Log.i("TokiCompatFixture",result);}
}'''
old='''package kotlin.jvm.internal; public class Intrinsics {
 public static void checkParameterIsNotNull(Object x,String n){if(x==null)throw new IllegalArgumentException(n);}
 public static void checkNotNullParameter(Object x,String n){if(x==null)throw new IllegalArgumentException(n);}
 public static void checkNotNullExpressionValue(Object x,String n){if(x==null)throw new IllegalArgumentException(n);}
 public static void checkExpressionValueIsNotNull(Object x,String n){if(x==null)throw new IllegalArgumentException(n);}
 public static boolean areEqual(Object a,Object b){return a==null?b==null:a.equals(b);}
}'''
reports=[]
for flavor in ('oldkotlin','plain'):
 d=tests/flavor;d.mkdir(exist_ok=True);src=d/'FixtureActivity.java';src.write_text(common,encoding='utf-8');sources=[src]
 if flavor=='oldkotlin':p=d/'Intrinsics.java';p.write_text(old,encoding='utf-8');sources.append(p)
 classes=d/'classes';classes.mkdir(exist_ok=True)
 run([jdk/'javac.exe','-source','8','-target','8','-cp',android,'-d',classes,*sources]);dex=d/'dex';dex.mkdir(exist_ok=True)
 run([bt/'d8.bat','--lib',android,'--output',dex,*classes.rglob('*.class')])
 package='io.github.meiyongai.toki.compatfixture.'+flavor
 manifest=f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="1.0"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="28"/><application android:label="Toki rootless fixture {flavor}" android:debuggable="true" android:hasCode="true"><activity android:name="io.github.meiyongai.toki.compatfixture.FixtureActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>'''
 (d/'AndroidManifest.xml').write_text(manifest,encoding='utf-8');apk=d/(flavor+'-unsigned.apk');run([bt/'aapt2.exe','link','-o',apk,'--manifest',d/'AndroidManifest.xml','-I',android])
 with zipfile.ZipFile(apk,'a',compression=zipfile.ZIP_DEFLATED)as z:z.write(dex/'classes.dex','classes.dex')
 reports.append(dict(package=package,artifact=str(apk),sha256=hashlib.sha256(apk.read_bytes()).hexdigest()))
# Compile ONLY our module entry. Stub API classes are compile-time only, never bundled in its DEX.
d=tests/'module';d.mkdir(exist_ok=True)
api={
 'de/robv/android/xposed/IXposedMod.java':'package de.robv.android.xposed; public interface IXposedMod {}',
 'de/robv/android/xposed/IXposedHookLoadPackage.java':'package de.robv.android.xposed;public interface IXposedHookLoadPackage extends IXposedMod {void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam p)throws Throwable;}',
 'de/robv/android/xposed/callbacks/XC_LoadPackage.java':'package de.robv.android.xposed.callbacks;public class XC_LoadPackage {public static class LoadPackageParam{public String packageName;public ClassLoader classLoader;}}',
 'FixtureModule.java':'package io.github.meiyongai.toki.compatfixture;public class FixtureModule implements de.robv.android.xposed.IXposedHookLoadPackage{public void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam p){if(p.packageName.startsWith("io.github.meiyongai.toki.compatfixture.")){System.setProperty("toki.compat.module","loaded");android.util.Log.i("TokiCompatModule","module entered for "+p.packageName);}}}'
}
for rel,s in api.items():p=d/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8')
classes=d/'classes';classes.mkdir(exist_ok=True);run([jdk/'javac.exe','-source','8','-target','8','-cp',android,'-d',classes,*[d/p for p in api]])
dex=d/'dex';dex.mkdir(exist_ok=True);run([bt/'d8.bat','--lib',android,'--classpath',classes,'--output',dex,classes/'io/github/meiyongai/toki/compatfixture/FixtureModule.class'])
manifest='''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.meiyongai.toki.compatfixture.module" android:versionCode="1" android:versionName="1.0"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="28"/><application android:label="Toki compatibility test module"><meta-data android:name="xposedmodule" android:value="true"/><meta-data android:name="xposeddescription" android:value="Synthetic framework regression fixture, not Toki"/><meta-data android:name="xposedminversion" android:value="93"/></application></manifest>'''
(d/'AndroidManifest.xml').write_text(manifest,encoding='utf-8');apk=d/'fixture-module-unsigned.apk';run([bt/'aapt2.exe','link','-o',apk,'--manifest',d/'AndroidManifest.xml','-I',android])
with zipfile.ZipFile(apk,'a',compression=zipfile.ZIP_DEFLATED)as z:z.write(dex/'classes.dex','classes.dex');z.writestr('assets/xposed_init','io.github.meiyongai.toki.compatfixture.FixtureModule\n')
reports.append(dict(package='io.github.meiyongai.toki.compatfixture.module',artifact=str(apk),sha256=hashlib.sha256(apk.read_bytes()).hexdigest()))
(w/'fixture-builds.json').write_text(json.dumps(reports,indent=2),encoding='utf-8');print(json.dumps(reports,indent=2))
