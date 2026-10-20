"""Reject a LSPatch APK that would fail VectorModuleManager's API namespace guard."""
import struct,sys,zipfile

def definitions(data, expected_super="Lio/github/meiyongai/toki/privateapi/XposedModule;"):
    sc,so=struct.unpack_from("<II",data,56);tc,to=struct.unpack_from("<II",data,64);cc,co=struct.unpack_from("<II",data,96)
    strings=[]
    for index in range(sc):
        offset=struct.unpack_from("<I",data,so+4*index)[0]
        while data[offset]&128: offset+=1
        offset+=1;strings.append(data[offset:data.index(0,offset)].decode("utf-8",errors="replace"))
    types=[strings[struct.unpack_from("<I",data,to+4*i)[0]] for i in range(tc)]
    for i in range(cc):
        offset=co+32*i
        if types[struct.unpack_from("<I",data,offset)[0]]=="Lio/github/meiyongai/toki/subtitle/hook/TokiModule;":
            assert types[struct.unpack_from("<I",data,offset+8)[0]]==expected_super, "Business code still references unrelocated API"
    return {types[struct.unpack_from("<I",data,co+32*i)[0]] for i in range(cc)}
def verify(path):
    with zipfile.ZipFile(path) as archive:
        classes=set()
        for name in archive.namelist():
            if name.endswith(".dex"): classes.update(definitions(archive.read(name)))
        assert not any(c.startswith(("Lde/robv/android/xposed/","Lio/github/libxposed/api/")) for c in classes), "Framework API definitions packaged under a forbidden namespace"
        assert "Lio/github/meiyongai/toki/privateapi/XposedModule;" in classes
        assert archive.read("assets/xposed_init").decode().strip()=="io.github.meiyongai.toki.subtitle.hook.LegacyTokiEntry"
        for resource in ("toki-host-rules.tsv","toki-extension-profile.properties","toki-host-shapes.tsv"):
            assert archive.getinfo(resource).file_size>0
        print("PASS: legacy entry, private API, resource profiles; no forbidden framework API classes")
if __name__=="__main__":verify(sys.argv[1])
