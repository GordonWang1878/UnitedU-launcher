import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image

def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).parents[1] / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class AssetsTest(unittest.TestCase):
    def setUp(self):
        self.a = load('gallery-assets')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.src = self.root / 'source'; self.src.mkdir()
        Image.new('RGB', (800, 450), 'red').save(self.src / 'card.png')
        self.item = dict(id='card-one', kind='cards', names={'zh-CN':'普通\n多行\\c', 'zh-TW':'literal\\n', 'en':'Normal'}, order=0, packages=['com.example.app'], credit='CC0', source='card.png')
    def generate(self):
        (self.src/'resources.json').write_text(json.dumps({'items':[self.item]}))
        return self.a.generate(self.src, None, self.root/'out', 1)
    def test_valid_preserves_original_and_text(self):
        result = self.generate()
        self.assertEqual(result['items'][0]['names'], self.item['names'])
        original = result['items'][0]['original']
        self.assertEqual((self.root/'out'/original['path']).read_bytes(), (self.src/'card.png').read_bytes())
        self.a.validate_artifact(self.root/'out')
    def test_bad_inputs(self):
        for key, value in [('id','../bad'), ('names',{'en':'a'}), ('packages',[]), ('source','../escape'), ('order',True), ('credit','')]:
            with self.subTest(key=key):
                old=self.item[key]; self.item[key]=value
                with self.assertRaises(ValueError): self.generate()
                self.item[key]=old
    def test_hdr_rejected(self):
        self.item['kind']='wallpapers'
        with self.assertRaisesRegex(ValueError, 'hdr-assets.py'): self.generate()
    def test_tamper_rejected(self):
        result=self.generate(); p=self.root/'out'/result['items'][0]['original']['path']; p.write_bytes(b'bad')
        with self.assertRaises(ValueError): self.a.validate_artifact(self.root/'out')
    def test_revision_and_kind(self):
        prior=self.generate(); previous=self.root/'out'/'index.json'
        with self.assertRaises(ValueError): self.a.generate(self.src, previous, self.root/'other', 1)
        self.item['kind']='screensavers'; (self.src/'resources.json').write_text(json.dumps({'items':[self.item]}))
        with self.assertRaises(ValueError): self.a.generate(self.src, previous, self.root/'other', 2)

    def test_content_update_and_retired_id(self):
        self.generate()
        Image.new('RGB',(800,450),'blue').save(self.src/'card.png')
        updated=self.a.generate(self.src,self.root/'out'/'index.json',self.root/'v2',2)
        self.assertNotEqual(updated['items'][0]['original']['sha256'], self.a.read_json(self.root/'out'/'index.json')['items'][0]['original']['sha256'])
        (self.src/'resources.json').write_text('{"items":[]}')
        self.a.generate(self.src,self.root/'v2'/'index.json',self.root/'v3',3)
        (self.src/'resources.json').write_text(json.dumps({'items':[self.item]}))
        restored=self.a.generate(self.src,self.root/'v3'/'index.json',self.root/'v4',4)
        self.assertEqual(restored['items'][0]['original']['sha256'],updated['items'][0]['original']['sha256'])
        (self.src/'resources.json').write_text('{"items":[]}')
        self.a.generate(self.src,self.root/'v4'/'index.json',self.root/'v5',5)
        Image.new('RGB',(800,450),'green').save(self.src/'card.png')
        (self.src/'resources.json').write_text(json.dumps({'items':[self.item]}))
        with self.assertRaisesRegex(ValueError,'retired ID'): self.a.generate(self.src,self.root/'v5'/'index.json',self.root/'v6',6)
    def test_duplicate_corrupt_oversize_and_symlink(self):
        (self.src/'resources.json').write_text(json.dumps({'items':[self.item,self.item]}))
        with self.assertRaisesRegex(ValueError,'duplicate ID'): self.a.generate(self.src,None,self.root/'dup',1)
        (self.src/'card.png').write_bytes(b'broken')
        with self.assertRaisesRegex(ValueError,'corrupt image'): self.generate()
        with (self.src/'card.png').open('wb') as stream: stream.truncate(32*1024*1024+1)
        with self.assertRaisesRegex(ValueError,'too large'): self.generate()
        (self.src/'card.png').unlink(); (self.src/'card.png').symlink_to(self.root/'outside.png')
        with self.assertRaisesRegex(ValueError,'symlink'): self.generate()
    def test_three_kinds_real_hdr(self):
        repo=Path(__file__).parents[2]
        items=[self.item]
        for kind in ('wallpapers','screensavers'):
            original=next((repo/'app/src/main/assets/builtin'/kind).glob('*.jpg'))
            target=self.src/(kind+'.jpg'); target.write_bytes(original.read_bytes())
            item=dict(self.item,id=kind,kind=kind,source=target.name,packages=[]); items.append(item)
        (self.src/'resources.json').write_text(json.dumps({'items':items}))
        result=self.a.generate(self.src,None,self.root/'three',1)
        self.assertEqual(len(result['items']),3); self.a.validate_artifact(self.root/'three')
    def test_dimension_and_bool_blob_rejected(self):
        Image.new('RGB',(8193,1)).save(self.src/'card.png')
        with self.assertRaisesRegex(ValueError,'dimension'): self.generate()
        Image.new('RGB',(30,30)).save(self.src/'card.png')
        result=self.generate(); result['items'][0]['original']['bytes']=True
        with self.assertRaises(ValueError): self.a.validate_catalog(result)

    def test_shared_source_migrates_v1_without_changing_id_or_original(self):
        repo=Path(__file__).parents[2]
        source=next((repo/'app/src/main/assets/builtin/wallpapers').glob('*.jpg'))
        (self.src/'wall.jpg').write_bytes(source.read_bytes())
        self.item.update(id='wall-old',kind='wallpapers',source='wall.jpg',packages=[])
        original=self.generate()
        # Keep a real legacy catalog/history as input even once the generator writes v2.
        prior=self.root/'out'; c=json.loads((prior/'index.json').read_text()); c['schemaVersion']=1
        for item in c['items']:
            item.pop('type',None); item.pop('usages',None); item['kind']='wallpapers'
        (prior/'index.json').write_text(json.dumps(c))
        history=json.loads((prior/'id-history.json').read_text())
        for value in history['ids'].values(): value['kind']='wallpapers'
        (prior/'id-history.json').write_text(json.dumps(history))
        self.item.pop('kind'); self.item.update(type='image',usages=['wallpapers','screensavers'])
        (self.src/'resources.json').write_text(json.dumps({'items':[self.item]}))
        current=self.a.generate(self.src,prior/'index.json',self.root/'v2',2)
        self.assertEqual(current['schemaVersion'],2)
        self.assertEqual(current['items'][0]['id'],'wall-old')
        self.assertEqual(current['items'][0]['original'],original['items'][0]['original'])
        self.assertEqual(current['items'][0]['usages'],['wallpapers','screensavers'])
        self.a.validate_artifact(self.root/'v2')
    def test_v2_video_and_mixed_card_source_are_rejected(self):
        self.item.pop('kind'); self.item.update(type='video',usages=['screensavers'])
        with self.assertRaises(ValueError): self.generate()
        self.item.update(type='image',usages=['cards','wallpapers'])
        with self.assertRaises(ValueError): self.generate()

if __name__=='__main__': unittest.main()

class HdrSpoofTest(unittest.TestCase):
    def test_marker_spoof_is_not_hdr(self):
        import io
        a=load('gallery-assets'); output=io.BytesIO(); Image.new('RGB',(32,32)).save(output,'JPEG')
        fake=output.getvalue()+b'hdrgm iso:ts:21496 MPF'+output.getvalue()
        with self.assertRaises(ValueError): a.image_info(fake,'wallpapers')
