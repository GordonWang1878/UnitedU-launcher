#!/usr/bin/env python3
"""Offline resource catalog builder; originals are never converted."""
import argparse
import hashlib
import io
import json
import re
import math
import os
import subprocess
import tempfile
from pathlib import Path
from PIL import Image

CATALOG_LIMIT=2*1024*1024
ORIGINAL_LIMIT=32*1024*1024
THUMB_LIMIT=512*1024
FORMATS={'JPEG':('jpg','image/jpeg'), 'PNG':('png','image/png'), 'WEBP':('webp','image/webp')}

def require(condition, message):
    if not condition: raise ValueError(message)

def integer(value, low, high):
    require(type(value) is int and low <= value <= high, 'invalid integer')

def text(value):
    require(isinstance(value,str) and value.strip(), 'missing nonblank text')

def safe_path(root, relative):
    text(relative)
    require(not any(c.isspace() or ord(c)<32 or c in '%\\?#:' for c in relative), 'unsafe path')
    parts=relative.split('/')
    require(all(p and p not in ('.','..') for p in parts) and not relative.startswith('/'), 'path traversal')
    no_symlink_ancestors(root)
    root=Path(root).resolve(); candidate=root.joinpath(*parts)
    require(all(not root.joinpath(*parts[:i]).is_symlink() for i in range(1,len(parts)+1)), 'symlink forbidden')
    require(candidate.resolve().is_relative_to(root), 'path escape')
    return candidate

def no_symlink_ancestors(path):
    require(all((not p.is_symlink() or str(p) in ('/tmp','/var')) for p in (Path(path).absolute(), *Path(path).absolute().parents)), "symlink ancestor forbidden")

def read_json(path, limit=CATALOG_LIMIT):
    no_symlink_ancestors(path)
    require(path.stat().st_size <= limit, 'JSON too large')
    def pairs(values):
        result={}
        for k,v in values:
            require(k not in result, 'duplicate JSON key'); result[k]=v
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=pairs)

def usages_of(item):
    if 'kind' in item:
        require('type' not in item and 'usages' not in item, 'mixed legacy and shared fields')
        require(item['kind'] in ('cards','wallpapers','screensavers'), 'invalid kind')
        return [item['kind']]
    require(item.get('type') == 'image', 'online gallery videos are not available')
    usages=item.get('usages')
    require(isinstance(usages,list) and bool(usages) and all(isinstance(v,str) and v in ('cards','wallpapers','screensavers') for v in usages), 'invalid usages')
    require(len(usages)==len(set(usages)), 'duplicate usages')
    require('cards' not in usages or usages==['cards'], 'card usage must be exclusive')
    return sorted(usages, key=('wallpapers','screensavers','cards').index)

def resource_family(item):
    return 'cards' if 'cards' in usages_of(item) else 'images'

def history_family(kind):
    require(kind in ('cards','wallpapers','screensavers','images'), 'invalid history kind')
    return 'cards' if kind=='cards' else 'images'

def validate_item(item):
    require(isinstance(item,dict), 'item must be object')
    require(isinstance(item.get('id'),str) and re.fullmatch('[a-z0-9][a-z0-9-]{0,95}',item['id']), 'invalid ID')
    usages=usages_of(item)
    require(isinstance(item.get('names'),dict), 'names must be object')
    for lang in ('zh-CN','zh-TW','en'): text(item['names'].get(lang))
    integer(item.get('order'),-2**31,2**31-1); text(item.get('credit'))
    packages=item.get('packages',[])
    require(isinstance(packages,list) and (packages or 'cards' not in usages), 'missing packages')
    for p in packages: require(isinstance(p,str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+',p), 'invalid package')
    require(len(packages)==len(set(packages)), 'duplicate package')

def jpeg_segments(data, start):
    require(data[start:start+2]==b'\xff\xd8','invalid JPEG association')
    cursor=start+2; segments=[]
    while True:
        require(cursor+4<=len(data) and data[cursor]==255,'invalid JPEG header')
        marker=data[cursor+1]; length=int.from_bytes(data[cursor+2:cursor+4],'big')
        require(length>=2 and cursor+2+length<=len(data),'invalid JPEG segment')
        segments.append((marker,cursor+4,cursor+2+length))
        if marker==0xda: return segments
        require(marker not in (0xd8,0xd9),'unexpected JPEG marker'); cursor+=2+length

def validate_hdr(data):
    """MPF association plus independent libultrahdr parses of both metadata encodings."""
    primary=jpeg_segments(data,0)
    mpf=[(a,b) for marker,a,b in primary if marker==0xe2 and data[a:b].startswith(b'MPF\0')]
    require(len(mpf)==1,'HDR requires one structured MPF index')
    try:
        with Image.open(io.BytesIO(data)) as image: mp=image._getmp()
        require(mp and mp.get(45057)==2 and len(mp.get(45058,[]))==2,'invalid MPF image index')
        first,second=mp[45058]; base=mpf[0][0]+4
        offset=base+second['DataOffset']; length=second['Size']
        require(first['DataOffset']==0 and first['Size']==offset and 0<length and offset+length==len(data),'inconsistent MPF association')
        require(data[offset-2:offset]==b'\xff\xd9' and data[-2:]==b'\xff\xd9','truncated MPF image')
        gain=jpeg_segments(data,offset)
        with Image.open(io.BytesIO(data[offset:])) as image: image.load()
    except (OSError,KeyError,TypeError,IndexError,SyntaxError) as error: raise ValueError('invalid HDR MPF/gainmap') from error
    xmp_header=b'http://ns.adobe.com/xap/1.0/\0'; iso_header=b'urn:iso:std:iso:ts:21496:-1\0'
    segments=primary+gain
    xmp=[(a,b) for marker,a,b in segments if marker==0xe1 and data[a:b].startswith(xmp_header)]
    iso=[(a,b) for marker,a,b in segments if marker==0xe2 and data[a:b].startswith(iso_header)]
    require(xmp and iso,'dual HDR metadata segments required')
    reader=os.environ.get('UHDR_READER','/opt/homebrew/bin/ultrahdr_app')
    metadata=[]
    with tempfile.TemporaryDirectory() as directory:
        for label,masked in [('Android14-XMP',iso),('Android15-ISO',xmp)]:
            variant=bytearray(data)
            # Keep APP segment lengths and MPF offsets intact; hide only the other encoding.
            for a,b in masked: variant[a:b]=bytes(b-a)
            probe=Path(directory)/(label+'.jpg'); probe.write_bytes(variant)
            try: result=subprocess.run([reader,'-m','1','-P','-j',str(probe)],capture_output=True,text=True,check=True,timeout=30)
            except (OSError,subprocess.SubprocessError) as error: raise ValueError('HDR probe failed; install libultrahdr or configure UHDR_READER') from error
            require('Ultra HDR Image: Yes' in result.stdout,f'invalid {label} metadata')
            values={}
            for line in result.stdout.splitlines():
                words=line.split()
                if words and words[0].startswith('--'):
                    try: values[words[0]]=[float(v) for v in words[1:]]
                    except ValueError as error: raise ValueError('invalid HDR metadata values') from error
            required=('--maxContentBoost','--minContentBoost','--gamma','--offsetSdr','--offsetHdr','--hdrCapacityMin','--hdrCapacityMax','--useBaseColorSpace')
            require(all(k in values and values[k] and all(math.isfinite(v) for v in values[k]) for k in required),'incomplete HDR metadata')
            require(all(v>0 for k in ('--maxContentBoost','--minContentBoost','--gamma','--hdrCapacityMin','--hdrCapacityMax') for v in values[k]),'invalid HDR gain parameters')
            metadata.append(values)
    for key in metadata[0]:
        require(key in metadata[1] and len(metadata[0][key])==len(metadata[1][key]) and all(abs(a-b)<=0.01*max(1,abs(a),abs(b)) for a,b in zip(metadata[0][key],metadata[1][key])),'inconsistent dual HDR metadata')

def image_info(data, kind, thumbnail=False):
    require(0<len(data)<= (THUMB_LIMIT if thumbnail else ORIGINAL_LIMIT), 'image too large')
    try:
        with Image.open(io.BytesIO(data)) as image:
            fmt=image.format; size=image.size; frames=getattr(image,'n_frames',1)
            require(fmt in FORMATS, 'unsupported image')
            require(all(1<=v<=8192 for v in size), 'image dimension too large')
            if thumbnail: require(fmt=='JPEG' and max(size)<=640 and frames==1, 'invalid SDR thumbnail')
            for frame in range(frames): image.seek(frame); image.load()
    except (OSError,Image.DecompressionBombError) as error: raise ValueError('corrupt image') from error
    hdr=b'hdrgm' in data or b'iso:ts:21496' in data or b'MPF' in data
    if kind!='cards' and not thumbnail:
        require(fmt=='JPEG','dual HDR JPEG required; run scripts/hdr-assets.py <input> <separate-output-directory>')
        try: validate_hdr(data)
        except ValueError as error: raise ValueError(f'{error}; run scripts/hdr-assets.py <input> <separate-output-directory> (SDR input cannot create HDR)') from error
    if kind=='cards' or thumbnail: require(not hdr, 'SDR image required')
    return size, FORMATS[fmt]

def validate_catalog(catalog):
    require(isinstance(catalog,dict), 'catalog must be object')
    integer(catalog.get('schemaVersion'),1,2); integer(catalog.get('revision'),0,2**63-1)
    items=catalog.get('items'); require(isinstance(items,list) and len(items)<=2000, 'invalid items')
    ids=set()
    for item in items:
        require(('kind' in item) == (catalog['schemaVersion']==1), 'schema fields mismatch')
        validate_item(item); require(item['id'] not in ids,'duplicate ID'); ids.add(item['id'])
        for key,limit in [('original',ORIGINAL_LIMIT),('thumbnail',THUMB_LIMIT)]:
            blob=item.get(key); require(isinstance(blob,dict),'missing blob')
            safe_path(Path('/tmp'),blob.get('path'))
            integer(blob.get('bytes'),1,limit)
            require(isinstance(blob.get('sha256'),str) and re.fullmatch('[a-f0-9]{64}',blob['sha256']), 'invalid SHA256')
            require(Path(blob['path']).stem==blob['sha256'], 'hash filename required')
        original=item['original']
        for dimension in ('width','height'): integer(original.get(dimension),1,8192)
        require(original.get('mediaType') in {v[1] for v in FORMATS.values()}, 'invalid mediaType')
    return catalog

def validate_history(audit, catalog):
    require(isinstance(audit,dict) and type(audit.get('revision')) is int and audit['revision']==catalog['revision'],'invalid history')
    history=audit.get('ids'); require(isinstance(history,dict),'invalid history')
    for id_, value in history.items():
        require(isinstance(id_,str) and re.fullmatch('[a-z0-9][a-z0-9-]{0,95}',id_) and isinstance(value,dict) and value.get('kind') in ('cards','wallpapers','screensavers','images') and type(value.get('active')) is bool,'invalid history entry')
        hashes=value.get('hashes'); require(isinstance(hashes,list) and bool(hashes) and all(isinstance(h,str) and re.fullmatch('[a-f0-9]{64}',h) for h in hashes),'invalid history hashes')
    active={i['id']:resource_family(i) for i in catalog['items']}
    require({k:history_family(v['kind']) for k,v in history.items() if v['active']}==active,'history mismatch')
    for item in catalog['items']: require(item['original']['sha256'] in history[item['id']]['hashes'],'history hash mismatch')
    return history

def validate_artifact(root):
    root=Path(root); require(not root.is_symlink(),'artifact symlink forbidden')
    catalog=validate_catalog(read_json(safe_path(root,'index.json')))
    for item in catalog['items']:
        for key in ('original','thumbnail'):
            blob=item[key]; path=safe_path(root,blob['path']); require(path.is_file(),'missing image')
            require(path.stat().st_size==blob['bytes'],'size mismatch'); data=path.read_bytes()
            require(hashlib.sha256(data).hexdigest()==blob['sha256'],'hash mismatch')
            size,(_,mime)=image_info(data,resource_family(item),key=='thumbnail')
            if key=='original': require(list(size)==[blob['width'],blob['height']] and mime==blob['mediaType'],'metadata mismatch')
    audit=read_json(safe_path(root,'id-history.json'))
    validate_history(audit,catalog)
    return catalog

def generate(source, previous, out, revision):
    source=Path(source); out=Path(out)
    no_symlink_ancestors(source); no_symlink_ancestors(out)
    require(not source.is_symlink() and not out.is_symlink(),'root symlink forbidden')
    integer(revision,0,2**63-1)
    require(not out.exists(),'output must be a new directory')
    require(not out.resolve().is_relative_to(source.resolve()),'output must be outside source')
    history={}; prior_items={}
    if previous:
        previous=Path(previous); prior=validate_catalog(read_json(previous)); require(revision>prior['revision'],'revision must increase')
        prior_items={i['id']:i for i in prior['items']}
        history={i['id']:{'kind':resource_family(i),'active':True,'hashes':[i['original']['sha256']]} for i in prior['items']}
        audit=previous.parent/'id-history.json'
        if audit.is_file():
            audit_value=read_json(audit)
            history=validate_history(audit_value,prior)
    resources=read_json(safe_path(source,'resources.json')); require(isinstance(resources,dict) and isinstance(resources.get('items'),list) and len(resources['items'])<=2000,'invalid resources')
    items=[]; seen=set(); prepared=[]
    for entry in resources['items']:
        validate_item(entry); id_=entry['id']; require(id_ not in seen,'duplicate ID'); seen.add(id_)
        if id_ in history: require(history_family(history[id_]['kind'])==resource_family(entry),'retired ID reuse or kind change')
        path=safe_path(source,entry.get('source')); require(path.is_file(),'missing source'); require(path.stat().st_size<=ORIGINAL_LIMIT,'image too large')
        data=path.read_bytes(); size,(suffix,mime)=image_info(data,resource_family(entry))
        if id_ in history and not history[id_]['active']:
            require(hashlib.sha256(data).hexdigest() in history[id_].get('hashes',[]),'retired ID reuse requires known original hash')
        image=Image.open(io.BytesIO(data)).convert('RGB'); image.thumbnail((640,640))
        buffer=io.BytesIO(); image.save(buffer,'JPEG',quality=85,optimize=True); thumb=buffer.getvalue(); image_info(thumb,'cards',True)
        item={k:entry[k] for k in ('id','names','order','credit')}; item.update(type='image',usages=usages_of(entry),packages=entry.get('packages',[]))
        for key,content,ext in [('original',data,suffix),('thumbnail',thumb,'jpg')]:
            digest=hashlib.sha256(content).hexdigest(); relative=f'{key}s/{digest}.{ext}'
            item[key]={'path':relative,'bytes':len(content),'sha256':digest}; prepared.append((relative,content))
        item['original'].update(width=size[0],height=size[1],mediaType=mime); items.append(item)
    catalog={'schemaVersion':2,'revision':revision,'items':items}; validate_catalog(catalog)
    encoded=json.dumps(catalog,ensure_ascii=False,indent=2).encode(); require(len(encoded)<=CATALOG_LIMIT,'catalog too large')
    for value in history.values(): value['active']=False
    for item in items:
        hashes=history.get(item['id'],{}).get('hashes',[])
        history[item['id']]={'kind':resource_family(item),'active':True,'hashes':sorted(set(hashes+[item['original']['sha256']]))}
    out.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as temp:
        staged=Path(temp)/'artifact'; staged.mkdir()
        for relative,data in prepared:
            dest=staged/relative; dest.parent.mkdir(exist_ok=True); dest.write_bytes(data)
        (staged/'index.json').write_bytes(encoded)
        changes={'added':sorted(seen-set(prior_items)), 'updated':sorted(i['id'] for i in items if i['id'] in prior_items and i != prior_items[i['id']]), 'removed':sorted(set(prior_items)-seen)}
        (staged/'changes.json').write_text(json.dumps(changes,ensure_ascii=False,indent=2))
        (staged/'id-history.json').write_text(json.dumps({'revision':revision,'ids':history},ensure_ascii=False))
        validate_artifact(staged); staged.rename(out)
    return catalog

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--source',required=True,type=Path); parser.add_argument('--previous',type=Path); parser.add_argument('--out',required=True,type=Path); parser.add_argument('--revision',required=True,type=int)
    args=parser.parse_args()
    try: generate(args.source,args.previous,args.out,args.revision)
    except (ValueError,OSError,KeyError,TypeError) as error: parser.exit(1,f'{error}\n')

if __name__=='__main__': main()
