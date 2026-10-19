"""Adapter written against myshell-ai/OpenVoice ToneColorConverter API.
Requires the upstream package and administrator-provided trusted checkpoints.
The neural model is NOT bundled in the Android APK.
"""
import io, tempfile, wave, threading, os
from collections import OrderedDict
from pathlib import Path

class OpenVoiceEngine:
    def __init__(self, config, checkpoint, device='cpu'):
        from openvoice.api import ToneColorConverter
        if not Path(config).is_file() or not Path(checkpoint).is_file():
            raise FileNotFoundError('Configuração e checkpoint OpenVoice necessários')
        if device == 'cpu':
            import torch
            threads = int(os.environ.get('OPENVOICE_THREADS', '2'))
            if not 1 <= threads <= 8:
                raise ValueError('OPENVOICE_THREADS precisa estar entre 1 e 8')
            torch.set_num_threads(threads)
        self.converter = ToneColorConverter(config, device=device)
        self.converter.load_ckpt(checkpoint)
        self.lock = threading.RLock()
        self.embeddings = OrderedDict()

    def _embedding(self, reference):
        reference = Path(reference)
        stat = reference.stat()
        key = (str(reference.resolve()), stat.st_mtime_ns, stat.st_size)
        if key not in self.embeddings:
            self.embeddings[key] = self.converter.extract_se(str(reference))
            while len(self.embeddings) > 64:
                self.embeddings.popitem(last=False)
        self.embeddings.move_to_end(key)
        return self.embeddings[key]

    def convert(self, source_wav: bytes, reference: Path) -> bytes:
        with self.lock:
            return self._convert(source_wav, reference)

    def _convert(self, source_wav, reference):
        import numpy as np
        import soundfile as sf
        from scipy.signal import resample_poly
        from math import gcd
        with tempfile.TemporaryDirectory(prefix='jarvis-voice-') as tmp:
            source = Path(tmp) / 'source.wav'
            output = Path(tmp) / 'output.wav'
            source.write_bytes(source_wav)
            src_se = self.converter.extract_se(str(source))
            target_se = self._embedding(reference)
            self.converter.convert(str(source), src_se=src_se, tgt_se=target_se,
                                   output_path=str(output), message='JarvisVoice')
            audio, rate = sf.read(output, dtype='float32')
            if audio.ndim > 1:
                audio = audio.mean(axis=1)
            if rate != 16000:
                common = gcd(rate, 16000)
                audio = resample_poly(audio, 16000//common, rate//common)
            pcm = (np.clip(audio, -1, 1) * 32767).astype('<i2').tobytes()
            result = io.BytesIO()
            with wave.open(result, 'wb') as wav:
                wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(16000)
                wav.writeframes(pcm)
            return result.getvalue()
