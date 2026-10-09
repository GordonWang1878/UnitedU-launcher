import test_gallery_assets as fixtures
load=fixtures.load
from unittest.mock import patch
import unittest

class PublishTest(unittest.TestCase):
    def setUp(self):
        fixtures.AssetsTest.setUp(self); self.p=load('gallery-publish'); fixtures.AssetsTest.generate(self); self.out=self.root/'out'
    def test_manifest_is_published_last(self):
        commands=self.p.build_upload_commands(self.out,'unitedu')
        self.assertTrue(commands[-1][0].endswith('/index.json'))
        self.assertTrue(all(not key.endswith('/index.json') for key,_ in commands[:-1]))
    def test_dry_run_no_side_effect(self):
        with patch.object(self.p.subprocess,'run',side_effect=AssertionError), patch.object(self.p.urllib.request,'build_opener',side_effect=AssertionError):
            self.p.publish(self.out,'unitedu','https://dl.uniteduone.com/gallery/')
    def test_failure_blocks_manifest(self):
        for fail_upload in (True,False):
            uploaded=[]
            def upload(key,path):
                uploaded.append(key)
                if fail_upload: raise RuntimeError('upload failed')
            def verify(url,path): raise RuntimeError('hash mismatch')
            with self.assertRaises(RuntimeError): self.p.publish(self.out,'unitedu','https://dl.uniteduone.com/gallery/',True,upload,verify)
            self.assertFalse(any(key.endswith('/index.json') for key in uploaded))
    def test_verified_order(self):
        events=[]
        self.p.publish(self.out,'unitedu','https://dl.uniteduone.com/gallery/',True,lambda k,p:events.append(('upload',k)),lambda u,p:events.append(('verify',u)))
        self.assertTrue(events[-1][1].endswith('/index.json'))
        self.assertEqual([e[0] for e in events],['upload','verify','upload','verify','upload'])
    def test_argv_not_shell(self):
        with patch.object(self.p.subprocess,'run') as run:
            self.p.upload('unitedu/gallery/originals/hash.png',self.out/'originals'/'literal\\c\n.png')
            argv=run.call_args.args[0]
            self.assertIsInstance(argv,list); self.assertEqual(run.call_args.kwargs,{'check':True})
            self.assertIn('literal\\c\n.png',argv[argv.index('--file')+1])

    def test_invalid_artifact_precedes_upload(self):
        (self.out/'index.json').write_text('{"schemaVersion":true,"revision":1,"items":[]}')
        with patch.object(self.p,'upload',side_effect=AssertionError('must not upload')):
            with self.assertRaises(ValueError): self.p.publish(self.out,'unitedu','https://dl.uniteduone.com/gallery/',True)
    def test_redirect_outside_root_rejected(self):
        for url in ('http://dl.uniteduone.com/gallery/a.jpg','https://evil.example/gallery/a.jpg','https://dl.uniteduone.com/gallery/../a.jpg','https://dl.uniteduone.com/gallery/%2e%2e/a.jpg'):
            with self.subTest(url=url), self.assertRaises(ValueError): self.p.validate_remote_url(url)

class RemoteVerifierTest(unittest.TestCase):
    def setUp(self):
        self.p=load('gallery-publish'); self.temp=fixtures.tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.path=fixtures.Path(self.temp.name)/'object.jpg'; self.path.write_bytes(b'abc')
        self.url='https://dl.uniteduone.com/gallery/originals/object.jpg'
    def response(self,body,url=None,delay=0):
        import io,time
        class Response(io.BytesIO):
            def geturl(inner): return url or self.url
            def read(inner,size=-1):
                if delay: time.sleep(delay)
                return super().read(size)
        return Response(body)
    def verify(self,response):
        with patch.object(self.p.urllib.request,'build_opener') as opener:
            opener.return_value.open.return_value=response
            self.p.verify_remote(self.url,self.path)
    def test_body_integrity(self):
        self.verify(self.response(b'abc'))
        for body in (b'xyz',b'ab',b'abcd'):
            with self.subTest(body=body),self.assertRaises(ValueError): self.verify(self.response(body))
    def test_final_redirect(self):
        self.verify(self.response(b'abc','https://dl.uniteduone.com/gallery/new.jpg'))
        with self.assertRaises(ValueError): self.verify(self.response(b'abc','https://evil.example/gallery/a.jpg'))
    def test_slow_read_absolute_deadline(self):
        import time
        start=time.monotonic()
        with patch.object(self.p,'TOTAL_TIMEOUT',0.03,create=True),self.assertRaisesRegex(ValueError,'total timeout'):
            self.verify(self.response(b'abc',delay=0.2))
        self.assertLess(time.monotonic()-start,0.15)
