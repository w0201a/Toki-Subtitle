"""Prepare an isolated API namespace for the LSPatch build. Never alters sources."""
from pathlib import Path
import io, struct, zipfile
ROOT = Path(__file__).resolve().parents[1]
OLD = b"io/github/libxposed/api"
NEW = b"io/github/meiyongai/toki/privateapi"
def relocate_class(data):
    if data[:4] != b"\xca\xfe\xba\xbe": raise ValueError("Not a class file")
    count=struct.unpack_from(">H",data,8)[0]
    out=bytearray(data[:10]);offset=10;index=1
    widths={3:4,4:4,5:8,6:8,7:2,8:2,9:4,10:4,11:4,12:4,15:3,16:2,17:4,18:4,19:2,20:2}
    while index<count:
        tag=data[offset];offset+=1;out.append(tag)
        if tag==1:
            length=struct.unpack_from(">H",data,offset)[0];offset+=2
            value=data[offset:offset+length].replace(OLD,NEW).replace(OLD.replace(b"/",b"."),NEW.replace(b"/",b"."))
            out.extend(struct.pack(">H",len(value)));out.extend(value);offset+=length
        else:
            size=widths[tag];out.extend(data[offset:offset+size]);offset+=size
            if tag in (5,6):index+=1
        index+=1
    out.extend(data[offset:]);return bytes(out)
def prepare(api_aar):
    output=ROOT/"app/build/legacy-api/relocated-api.jar";output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(api_aar) as aar:
        jar=aar.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(jar)) as source,zipfile.ZipFile(output,"w",zipfile.ZIP_DEFLATED) as target:
        for item in source.infolist():
            if item.filename.endswith(".class"):
                target.writestr(item.filename.replace(OLD.decode(),NEW.decode()),relocate_class(source.read(item)))
    for variant in ("main","lspatch"):
        source=ROOT/f"app/src/{variant}/java";dest=ROOT/f"app/build/legacy-src/{variant}"
        for file in source.rglob("*"):
            if file.suffix not in (".kt",".java"):continue
            output=dest/file.relative_to(source);output.parent.mkdir(parents=True,exist_ok=True)
            output.write_text(file.read_text(encoding="utf-8").replace("io.github.libxposed.api","io.github.meiyongai.toki.privateapi"),encoding="utf-8")
if __name__=="__main__":
    import sys
    prepare(Path(sys.argv[1]))
