#!/usr/bin/env python3
"""Review locally by default. --publish is an explicit external mutation."""
import argparse
import hashlib
import importlib.util
import mimetypes
import re
import subprocess
import signal
import threading
import time
from contextlib import contextmanager
import urllib.parse
import urllib.request
from pathlib import Path

spec=importlib.util.spec_from_file_location('gallery_assets',Path(__file__).with_name('gallery-assets.py'))
assets=importlib.util.module_from_spec(spec); spec.loader.exec_module(assets)

# Cloudflare 对默认的 Python-urllib UA 返回 403(2026-10-11 首次上线实测),校验读取自报身份。
USER_AGENT='UnitedU-gallery-publish/1'

def build_upload_commands(artifact,bucket):
    assets.require(isinstance(bucket,str) and re.fullmatch('[a-z0-9][a-z0-9-]{0,62}',bucket),'invalid bucket')
    root=Path(artifact); catalog=assets.validate_artifact(root)
    paths=sorted({item[key]['path'] for item in catalog['items'] for key in ('original','thumbnail')})
    return [(f'{bucket}/gallery/{p}',root/p) for p in paths]+[(f'{bucket}/gallery/index.json',root/'index.json')]

def base_root(url):
    value=urllib.parse.urlsplit(url)
    assets.require(value.scheme=='https' and value.netloc=='dl.uniteduone.com' and value.path=='/gallery/' and not value.query and not value.fragment,'base URL must be https://dl.uniteduone.com/gallery/')
    return url

class RedirectGuard(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        validate_remote_url(newurl)
        return super().redirect_request(req,fp,code,msg,headers,newurl)

def validate_remote_url(url):
    parsed=urllib.parse.urlsplit(url)
    assets.require(parsed.scheme=='https' and parsed.netloc=='dl.uniteduone.com' and parsed.path.startswith('/gallery/') and not parsed.query and not parsed.fragment,'unsafe remote URL')
    assets.safe_path(Path('/tmp'),parsed.path[len('/gallery/'):])

TOTAL_TIMEOUT=120

@contextmanager
def absolute_deadline(seconds):
    # The CLI runs on the main thread on macOS/Linux. SIGALRM interrupts a
    # buffered read even when a trickling peer continually resets socket timeout.
    assets.require(threading.current_thread() is threading.main_thread(),'verification deadline requires main thread')
    assets.require(hasattr(signal,'setitimer'),'verification deadline requires Unix timers')
    previous=signal.getsignal(signal.SIGALRM); existing=signal.getitimer(signal.ITIMER_REAL)
    assets.require(existing==(0.0,0.0),'verification cannot replace an existing process timer')
    def expired(signum,frame): raise ValueError('remote total timeout')
    signal.signal(signal.SIGALRM,expired); signal.setitimer(signal.ITIMER_REAL,seconds)
    try: yield
    finally:
        signal.setitimer(signal.ITIMER_REAL,0); signal.signal(signal.SIGALRM,previous)

def verify_remote(url,path):
    validate_remote_url(url)
    size=path.stat().st_size; digest=hashlib.sha256(); total=0
    import time
    started=time.monotonic()
    with absolute_deadline(TOTAL_TIMEOUT), urllib.request.build_opener(RedirectGuard()).open(urllib.request.Request(url,headers={'User-Agent':USER_AGENT}),timeout=10) as response:
        validate_remote_url(response.geturl())
        while True:
            assets.require(time.monotonic()-started<=TOTAL_TIMEOUT,'remote total timeout')
            chunk=response.read(min(65536,size-total+1))
            if not chunk: break
            total+=len(chunk); assets.require(total<=size,'remote oversized'); digest.update(chunk)
    assets.require(total==size and digest.hexdigest()==hashlib.sha256(path.read_bytes()).hexdigest(),'remote hash/size mismatch')

def upload(key,path):
    mime='application/json' if path.name=='index.json' else mimetypes.guess_type(path.name)[0]
    cache='public, max-age=60' if path.name=='index.json' else 'public, max-age=31536000, immutable'
    subprocess.run(['wrangler','r2','object','put',key,'--file',str(path),'--content-type',mime,'--cache-control',cache,'--remote'],check=True)

def publish(artifact,bucket,base_url,publish=False,uploader=None,verifier=None):
    base_root(base_url); commands=build_upload_commands(artifact,bucket)
    if not publish: return commands
    uploader=uploader or upload; verifier=verifier or verify_remote
    # All validation precedes even the first uploader call.
    for key,path in commands[:-1]:
        uploader(key,path); verifier(base_url+key.split('/gallery/',1)[1],path)
    uploader(*commands[-1])
    return commands

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--artifact',required=True,type=Path); parser.add_argument('--bucket',default='unitedu'); parser.add_argument('--base-url',default='https://dl.uniteduone.com/gallery/')
    mode=parser.add_mutually_exclusive_group(); mode.add_argument('--dry-run',action='store_true'); mode.add_argument('--publish',action='store_true')
    args=parser.parse_args()
    try:
        commands=publish(args.artifact,args.bucket,args.base_url,args.publish)
        for key,path in commands: print(f'{key} <- {path}')
    except (ValueError,OSError,KeyError,TypeError,subprocess.CalledProcessError) as error: parser.exit(1,f'{error}\n')

if __name__=='__main__': main()
