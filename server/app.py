"""Private audio relay, optional recording conversion and signed telephone gateway.
No carrier audio interception or hidden recording.
"""
from __future__ import annotations
import asyncio, base64, binascii, hmac, io, json, os, re, secrets, struct, time, wave
from pathlib import Path
from dataclasses import dataclass, field
from aiohttp import web, WSMsgType


def decode_audio(encoded: str, minimum: float = 1, maximum: float = 60) -> bytes:
    if not isinstance(encoded, str) or len(encoded) > 6_000_000:
        raise ValueError('Áudio ausente ou muito grande')
    try:
        data = base64.b64decode(encoded, validate=True)
        with wave.open(io.BytesIO(data), 'rb') as wav:
            duration = wav.getnframes() / wav.getframerate()
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getframerate() != 16000 or wav.getcomptype() != 'NONE':
                raise ValueError('Use WAV PCM16 mono a 16000 Hz')
            if not minimum <= duration <= maximum:
                raise ValueError(f'Duração permitida: {minimum:g} a {maximum:g} segundos')
            pcm = wav.readframes(wav.getnframes())
            if len(pcm) != wav.getnframes() * 2:
                raise ValueError('WAV truncado')
        return data
    except (binascii.Error, wave.Error, EOFError, ZeroDivisionError) as exc:
        raise ValueError('WAV ou base64 inválido') from exc


@dataclass(eq=False)
class Peer:
    socket: web.WebSocketResponse
    accepted: bool = False
    config_version: int = 0
    pending_ready: int = 0
    joined: float = field(default_factory=time.monotonic)
    budget: float = 6400
    updated: float = field(default_factory=time.monotonic)


class State:
    def __init__(self, token: str, data_dir: Path, engine=None):
        if len(token) < 24:
            raise ValueError('JARVIS_TOKEN precisa ter pelo menos 24 caracteres')
        self.token, self.data_dir, self.engine = token, data_dir, engine
        self.rooms: dict[str, list[Peer]] = {}
        self.lock = asyncio.Lock()
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.api_slots = asyncio.Semaphore(2)

    def voices(self):
        result = []
        for p in sorted(self.data_dir.glob('*/metadata.json')):
            try:
                meta = json.loads(p.read_text())
                result.append({'id': p.parent.name, 'name': meta['name']})
            except (OSError, KeyError, ValueError):
                continue
        return result


@web.middleware
async def access(request, handler):
    if request.path == '/healthz' and request.method == 'GET':
        return web.json_response({'status': 'ok'})
    if request.path.startswith('/telephony/'):
        return await handler(request)  # provider routes validate their own Twilio signature
    expected = 'Bearer ' + request.app['state'].token
    if not hmac.compare_digest(request.headers.get('Authorization', '').encode(), expected.encode()):
        return web.json_response({'error': 'Acesso não autorizado'}, status=401)
    try:
        return await handler(request)
    except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
        return web.json_response({'error': str(exc) or 'Requisição inválida'}, status=400)


async def health(request):
    state = request.app['state']
    return web.json_response({'version': '0.2.0', 'engine_ready': state.engine is not None,
                             'conversion': 'recordings_only', 'calls': 'app_to_app_foreground',
                             'carrier_audio': False, 'phone_gateway_ready': request.app.get('phone') is not None})


async def voices(request):
    state = request.app['state']
    if request.method == 'GET':
        return web.json_response({'voices': state.voices()})
    if len(state.voices()) >= 32:
        raise ValueError('Limite de 32 referências. Exclua uma para cadastrar outra.')
    data = await request.json()
    if data.get('authorized') is not True:
        raise ValueError('Confirme autorização para usar esta referência')
    name = data.get('name', '').strip()
    if not 1 <= len(name) <= 60:
        raise ValueError('Nome deve ter 1 a 60 caracteres')
    audio = decode_audio(data.get('audio'), 3, 30)
    identifier = secrets.token_hex(16)
    folder = state.data_dir / identifier
    folder.mkdir(mode=0o700)
    (folder / 'reference.wav').write_bytes(audio)
    (folder / 'metadata.json').write_text(json.dumps({'name': name, 'authorized': True, 'created': int(time.time())}))
    return web.json_response({'id': identifier, 'name': name, 'engine_ready': state.engine is not None}, status=201)


def voice_folder(state, identifier):
    if not re.fullmatch(r'[a-f0-9]{32}', identifier):
        raise ValueError('Identificador de voz inválido')
    folder = state.data_dir / identifier
    if not (folder / 'reference.wav').is_file():
        raise ValueError('Referência não encontrada')
    return folder


async def delete_voice(request):
    state = request.app['state']
    folder = voice_folder(state, request.match_info['identifier'])
    async with state.lock:
        for file in folder.iterdir():
            if file.is_file():
                file.unlink()
        folder.rmdir()
    return web.json_response({'deleted': True})


async def convert(request):
    state = request.app['state']
    if state.engine is None:
        return web.json_response({'error': 'Motor OpenVoice não instalado ou não carregado neste servidor'}, status=503)
    data = await request.json()
    audio = decode_audio(data.get('audio'), 1, 60)
    folder = voice_folder(state, data.get('voice_id', ''))
    async with state.api_slots:
        async with state.lock:
            result = await asyncio.to_thread(state.engine.convert, audio, folder / 'reference.wav')
    return web.json_response({'audio': base64.b64encode(result).decode(), 'engine': 'OpenVoice', 'live': False})


async def send_event(peers, type_):
    for peer in list(peers):
        if not peer.socket.closed:
            try:
                await asyncio.wait_for(peer.socket.send_json({'type': type_}), .5)
            except (ConnectionError, asyncio.TimeoutError, RuntimeError):
                await peer.socket.close()


async def signal(request):
    state = request.app['state']
    room = request.match_info['room']
    if not re.fullmatch(r'[A-Za-z0-9_-]{6,64}', room):
        raise ValueError('Sala inválida')
    if len(state.rooms) >= 50 and room not in state.rooms:
        return web.json_response({'error': 'Servidor ocupado'}, status=503)
    peers = state.rooms.setdefault(room, [])
    if len(peers) >= 2:
        return web.json_response({'error': 'Sala cheia'}, status=409)
    ws = web.WebSocketResponse(max_msg_size=2048, heartbeat=20)
    # Reserve before the upgrade await to avoid a concurrent third participant.
    peer = Peer(ws)
    peers.append(peer)
    try:
        await ws.prepare(request)
        await send_event([p for p in peers if p.socket.prepared], 'ringing' if len(peers) == 2 else 'waiting')
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    event = json.loads(msg.data)
                except ValueError:
                    await ws.close(code=1007, message=b'invalid json')
                    break
                if not isinstance(event, dict):
                    await ws.close(code=1007, message=b'invalid event')
                    break
                if event.get('type') == 'configure':
                    version=event.get('version')
                    if type(version) is not int or not 1 <= version <= 2147483647:
                        await ws.close(code=1008, message=b'invalid configuration')
                        break
                    if version != peer.config_version:
                        peer.config_version=version
                        peer.pending_ready=version
                if event.get('type') == 'accept':
                    peer.accepted = True
                    if len(peers) == 2 and all(p.accepted for p in peers):
                        await send_event(peers, 'active')
            elif msg.type == WSMsgType.BINARY:
                pcm=msg.data
                frame_version=0
                if peer.config_version and pcm.startswith(b'JV') and len(pcm)>6:
                    frame_version=struct.unpack('>I',pcm[2:6])[0]
                    pcm=pcm[6:]
                if not 0 < len(pcm) <= 640 or len(pcm) % 2:
                    await ws.close(code=1008, message=b'invalid pcm16 frame')
                    break
                if peer.config_version and frame_version != peer.config_version:
                    continue
                now = time.monotonic()
                peer.budget = min(6400, peer.budget + (now-peer.updated)*35000)
                peer.updated = now
                peer.budget -= len(msg.data)
                if peer.budget < 0:
                    await ws.close(code=1008, message=b'audio rate exceeded')
                    break
                if len(peers) == 2 and all(p.accepted for p in peers):
                    for other in peers:
                        if other is not peer and not other.socket.closed:
                            try:
                                await asyncio.wait_for(other.socket.send_bytes(pcm), .25)
                                if peer.pending_ready and frame_version == peer.pending_ready:
                                    version=peer.pending_ready
                                    if hasattr(other.socket,'mark_ready'):
                                        await other.socket.mark_ready(peer,version)
                                    else:
                                        await peer.socket.send_json({'type':'ready','version':version,'confirmation':'route_sent'})
                                    peer.pending_ready=0
                            except (ConnectionError, asyncio.TimeoutError, RuntimeError):
                                await other.socket.close()
            elif msg.type == WSMsgType.ERROR:
                break
    finally:
        if request.app.get('phone') and room in request.app['phone'].calls:
            try:
                await request.app['phone'].hangup(room)
            except Exception:
                pass
        if peer in peers:
            peers.remove(peer)
        for other in peers:
            other.accepted = False
        await send_event(peers, 'ended')
        if not peers:
            state.rooms.pop(room, None)
    return ws


async def shutdown(app):
    if app.get('phone'):
        await app['phone'].close()
    peers = [p for room in app['state'].rooms.values() for p in room]
    await asyncio.gather(*(p.socket.close(code=1001, message=b'shutdown') for p in peers), return_exceptions=True)


def create_app(token: str, data_dir: Path, engine=None, phone_config=None):
    app = web.Application(client_max_size=6_000_000, middlewares=[access])
    app['state'] = State(token, data_dir, engine)
    app.add_routes([web.get('/healthz', health), web.get('/api/health', health), web.get('/api/voices', voices),
                    web.post('/api/voices', voices), web.delete('/api/voices/{identifier}', delete_voice),
                    web.post('/api/convert', convert), web.get('/signal/{room}', signal)])
    if phone_config is not None:
        from phone import install
        install(app,phone_config)
    else:
        async def phone_unavailable(request):
            return web.json_response({'error':'Central telefônica não configurada neste servidor'},status=503)
        app.router.add_post('/api/phone/outgoing',phone_unavailable)
    app.on_shutdown.append(shutdown)
    return app


if __name__ == '__main__':
    token = os.environ.get('JARVIS_TOKEN', '')
    engine = None
    config = os.environ.get('OPENVOICE_CONFIG')
    checkpoint = os.environ.get('OPENVOICE_CHECKPOINT')
    if config and checkpoint:
        from openvoice_engine import OpenVoiceEngine
        # Fail startup on invalid model config instead of claiming the engine is ready.
        engine = OpenVoiceEngine(config, checkpoint, os.environ.get('OPENVOICE_DEVICE', 'cpu'))
    phone_config = None
    if os.environ.get('ENABLE_TELEPHONY') == 'true':
        from phone import PhoneConfig
        phone_config = PhoneConfig.from_env()
    web.run_app(create_app(token, Path(os.environ.get('JARVIS_DATA', './data')), engine, phone_config),
                host=os.environ.get('JARVIS_BIND', '127.0.0.1'), port=int(os.environ.get('PORT', '8080')),
                access_log=None)
