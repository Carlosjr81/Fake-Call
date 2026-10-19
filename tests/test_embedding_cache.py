"""Cache behavior only: explicitly does not replace the real-model diagnostic."""
import os, sys, tempfile, threading, unittest
from collections import OrderedDict
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from openvoice_engine import OpenVoiceEngine


class EmbeddingCacheTests(unittest.TestCase):
    def test_reference_reuse_change_isolation_and_memory_bound(self):
        class Extractor:
            def __init__(self): self.calls=0
            def extract_se(self,path):
                self.calls+=1
                return (self.calls,Path(path).read_bytes())
        engine=OpenVoiceEngine.__new__(OpenVoiceEngine)
        engine.converter=Extractor();engine.embeddings=OrderedDict();engine.lock=threading.RLock()
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);a=root/'first.wav';a.write_bytes(b'voice-a')
            first=engine._embedding(a)
            self.assertIs(first,engine._embedding(a))
            self.assertEqual(1,engine.converter.calls)
            a.write_bytes(b'replacement-voice')
            self.assertNotEqual(first,engine._embedding(a))
            b=root/'other'/'first.wav';b.parent.mkdir();b.write_bytes(b'voice-b')
            self.assertNotEqual(engine._embedding(a),engine._embedding(b))
            for i in range(70):
                p=root/f'{i}.wav';p.write_bytes(str(i).encode());engine._embedding(p)
            self.assertEqual(64,len(engine.embeddings))


if __name__=='__main__': unittest.main()
