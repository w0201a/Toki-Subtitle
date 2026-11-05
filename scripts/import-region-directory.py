from pathlib import Path
import collections,xml.etree.ElementTree as E,unicodedata,zipfile,json,hashlib
import argparse
parser=argparse.ArgumentParser(description="Build the bundled geography directory from GeoNames admin1/cities1000 and Unicode CLDR snapshots; source hashes must be supplied in sources.json.")
parser.add_argument("input_dir", type=Path)
parser.add_argument("--output-dir", type=Path, default=Path("app/src/main/assets/geography"))
args=parser.parse_args()
p=args.input_dir
for source in json.loads((p/'sources.json').read_text(encoding='utf-8')):
 filename=source['file']
 if Path(filename).name != filename:raise ValueError('Input filename must be a basename')
 if hashlib.sha256((p/filename).read_bytes()).hexdigest()!=source['sha256']:raise ValueError('Input hash mismatch: '+filename)
def labels(file):return {e.attrib['type']:''.join(e.itertext()) for e in E.parse(file).findall('.//subdivision') if e.attrib.get('type')}
en=labels(p/'subdivisions-en.xml');zh=labels(p/'subdivisions-zh.xml')
def norm(s):return ''.join(c for c in unicodedata.normalize('NFKD',s).casefold() if c.isalnum())
names=collections.defaultdict(set)
for code,name in en.items():names[(code[:2].upper(),norm(name))].add(code)
admins={r.split('\t')[0]:r.split('\t') for r in (p/'admin1CodesASCII.txt').read_text().splitlines()};points={}
with zipfile.ZipFile(p/'cities1000.zip') as z:
 for line in z.read('cities1000.txt').decode().splitlines():
  row=line.split('\t');code=row[8]+'.'+row[10]
  if code not in admins or not row[17]:continue
  score=({'PPLA':3,'PPLC':2,'PPLA2':1,'PPLA3':0}.get(row[7],-1),int(row[14]),int(row[0]))
  if code not in points or score>points[code][0]:points[code]=(score,row)
rows=[];localized=0
for code,r in sorted(admins.items()):
 candidates=names.get((code[:2],norm(r[1])),set())|names.get((code[:2],norm(r[2])),set())
 local=zh.get(next(iter(candidates)),'') if len(candidates)==1 else ''
 if local:localized+=1
 city=points.get(code,(None,None))[1]
 row=[code,r[1],local,r[3],city[1] if city else '',city[4] if city else '',city[5] if city else '',city[17] if city else '',city[0] if city else '']
 assert all('\t' not in x and '\n' not in x and '\r' not in x for x in row)
 rows.append('\t'.join(row))
out=args.output_dir;out.mkdir(parents=True,exist_ok=True)
text='# toki-admin-regions-v1\n# code\tname\tzh\tadmin_geoname_id\tcity\tlatitude\tlongitude\ttimezone\tcity_geoname_id\n'+'\n'.join(rows)+'\n';(out/'admin-regions.tsv').write_text(text,encoding='utf-8',newline='\n')
manifest={'format':1,'generatedDate':'2026-11-05','regions':len(rows),'countries':len({x[:2] for x in admins}),'withRepresentativePoint':len(points),'localizedChineseByUniqueEnglishName':localized,'noRepresentativePoint':len(rows)-len(points),'sources':json.loads((p/'sources.json').read_text()),'outputSha256':hashlib.sha256(text.encode()).hexdigest(),'mapping':'GeoNames country.admin1 joins city country/admin1 exactly. Prefer first-level seat, then national capital, lower-level seat, population. Chinese names use a unique per-country normalized CLDR English match; no guessing ISO suffixes from GeoNames FIPS codes.'}
(out/'sources.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(out/'NOTICE.txt').write_text('''Administrative region names and representative place coordinates/time zones: GeoNames, https://www.geonames.org/ . Licensed under Creative Commons Attribution 4.0: https://creativecommons.org/licenses/by/4.0/ . Modified by joining the published admin1 codes and cities1000 tables, choosing a representative seat/city, and adding selected Chinese labels. Source URLs and SHA-256 values are recorded in sources.json. Data is supplied as-is and does not establish service availability, network/IP location or administrative boundary accuracy.

Selected Chinese subdivision names: Unicode CLDR, Copyright Unicode, Inc. Licensed under the Unicode License V3 (https://www.unicode.org/license.txt). English-to-Chinese matches are used only when unique within a country. GeoNames admin codes are not presented as ISO 3166-2 codes.
''',encoding='utf-8')
print(json.dumps({k:v for k,v in manifest.items() if k not in ['sources','mapping']},ensure_ascii=False,indent=2))
