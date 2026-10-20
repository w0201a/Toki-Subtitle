"""Check both runtime boundaries in the single distributable APK."""
import io,sys,zipfile
from importlib.machinery import SourceFileLoader
from pathlib import Path
legacy=SourceFileLoader("verify_legacy",str(Path(__file__).with_name("verify-lspatch.py"))).load_module()
def classes(archive,superclass):
    result=set()
    for name in archive.namelist():
        if name.endswith(".dex"): result.update(legacy.definitions(archive.read(name),superclass))
    return result
with zipfile.ZipFile(sys.argv[1]) as outer:
    primary=classes(outer,"Lio/github/libxposed/api/XposedModule;")
    assert not any(c.startswith(("Lde/robv/android/xposed/","Lio/github/libxposed/api/")) for c in primary)
    assert "Lio/github/meiyongai/toki/subtitle/bootstrap/UniversalLegacyEntry;" in primary
    assert outer.read("assets/xposed_init").decode().strip().endswith("UniversalLegacyEntry")
    assert outer.read("META-INF/xposed/java_init.list").decode().strip().endswith("TokiModule")
    with zipfile.ZipFile(io.BytesIO(outer.read("assets/toki/legacy-payload.apk"))) as payload:
        inner=classes(payload,"Lio/github/meiyongai/toki/privateapi/XposedModule;")
        assert not any(c.startswith(("Lde/robv/android/xposed/","Lio/github/libxposed/api/")) for c in inner)
        assert "Lio/github/meiyongai/toki/privateapi/XposedModule;" in inner
        for key in ("toki-host-rules.tsv","toki-extension-profile.properties"):
            assert outer.read(key)==payload.read(key),"Runtime discovery profiles differ"
print("PASS: unified modern entry, legacy bootstrap, isolated payload, matching profiles, no forbidden API definitions")
