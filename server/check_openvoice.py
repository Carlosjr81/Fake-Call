"""Explicit real-model check; no model doubles and no network phone calls.
User-provided source/reference recordings stay on this machine.
"""
import argparse, io, json, time, wave, resource, hashlib, statistics
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for field in ['config','checkpoint','source','reference','output','report']:
        parser.add_argument('--'+field,required=True,type=Path)
    parser.add_argument('--runs',type=int,default=3)
    parser.add_argument('--threads',type=int,default=2)
    args=parser.parse_args()
    if not 2 <= args.runs <= 10 or not 1 <= args.threads <= 8:
        parser.error('Use 2–10 execuções e 1–8 threads')
    import torch
    from openvoice_engine import OpenVoiceEngine
    from app import decode_audio
    import base64
    torch.set_num_threads(args.threads)
    source=decode_audio(base64.b64encode(args.source.read_bytes()).decode(),1,60)
    start=time.perf_counter()
    engine=OpenVoiceEngine(str(args.config),str(args.checkpoint))
    load_seconds=time.perf_counter()-start
    rows=[]
    with wave.open(io.BytesIO(source)) as wav:
        source_duration=wav.getnframes()/wav.getframerate()
    for run in range(args.runs):
        start=time.perf_counter()
        result=engine.convert(source,args.reference)
        elapsed=time.perf_counter()-start
        with wave.open(io.BytesIO(result)) as wav:
            duration=wav.getnframes()/wav.getframerate()
            assert (wav.getnchannels(),wav.getsampwidth(),wav.getframerate())==(1,2,16000)
            assert .95 <= duration/source_duration <= 1.05, 'Duração inesperada'
            pcm=wav.readframes(wav.getnframes())
            import array
            samples=array.array('h',pcm)
            assert any(abs(sample)>1 for sample in samples), 'Saída silenciosa'
        rows.append({'run':run,'audio_seconds':duration,'processing_seconds':elapsed,
                     'realtime_factor':elapsed/source_duration})
    args.output.write_bytes(result)
    report={'model':'OpenVoice V2','checkpoint_sha256':hashlib.sha256(args.checkpoint.read_bytes()).hexdigest(),
            'torch':torch.__version__,'threads':args.threads,'load_seconds':load_seconds,
            'max_rss_kib_linux':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'runs':rows,
            'median_warm_realtime_factor':statistics.median(r['realtime_factor'] for r in rows[1:]),
            'live_call_verified':False,'perceptual_identity_verified':False}
    args.report.write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
