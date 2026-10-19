"""Optional PSTN bridge. Disabled unless explicitly configured by administrator.
The paid provider is never contacted by local tests. This is not SIM interception.
"""
from __future__ import annotations
import asyncio, base64, os, re, secrets, time, audioop
from dataclasses import dataclass
from xml.etree.ElementTree import Element, SubElement, tostring
from aiohttp import web, ClientSession, ClientTimeout, BasicAuth, WSMsgType

@dataclass
class PhoneConfig:
    account: str
    secret: str
    from_number: str
    public_url: str
    incoming_room: str
    allowed: set[str]
    time_limit: int = 120

    def __post_init__(self):
        if not re.fullmatch(r'AC[a-fA-F0-9]{32}',self.account):raise ValueError('TWILIO_ACCOUNT_SID inválido')
        if len(self.secret)<24:raise ValueError('TWILIO_AUTH_TOKEN inválido')
        if not re.fullmatch(r'\+[1-9][0-9]{7,14}',self.from_number):raise ValueError('TWILIO_FROM_NUMBER inválido')
        from urllib.parse import urlparse
        url=urlparse(self.public_url)
        if url.scheme!='https' or not url.hostname or url.path not in ('','/') or url.query or url.username:raise ValueError('JARVIS_PUBLIC_URL precisa ser uma origem HTTPS')
        self.public_url=self.public_url.rstrip('/')
        if not re.fullmatch(r'[A-Za-z0-9_-]{6,64}',self.incoming_room):raise ValueError('JARVIS_INCOMING_ROOM inválido')
        if not 30<=self.time_limit<=300:raise ValueError('Limite de chamada: 30 a 300 segundos')

    @classmethod
    def from_env(cls):
        if os.environ.get('ENABLE_TELEPHONY')!='true':return None
        return cls(os.environ['TWILIO_ACCOUNT_SID'],os.environ['TWILIO_AUTH_TOKEN'],os.environ['TWILIO_FROM_NUMBER'],
                   os.environ['JARVIS_PUBLIC_URL'],os.environ.get('JARVIS_INCOMING_ROOM','telefone-pessoal'),
                   {n.strip() for n in os.environ.get('JARVIS_ALLOWED_NUMBERS','').split(',') if n.strip()},
                   int(os.environ.get('PHONE_TIME_LIMIT','120')))

@dataclass
class PhoneCall:
    room: str
    ticket: str
    sid: str = ''
    cancelled: bool = False
    created: float = 0
    socket: object = None
    timeout_task: object = None

class PhoneSocket:
    def __init__(self,ws,stream):
        self.ws,self.stream=ws,stream
        self.prepared=True
        self.down_state=None
        self.pending_marks={}
    @property
    def closed(self):return self.ws.closed
    async def send_json(self,event):pass # call state is controlled by the media stream
    async def send_bytes(self,pcm):
        down,self.down_state=audioop.ratecv(pcm,2,1,16000,8000,self.down_state)
        encoded=base64.b64encode(audioop.lin2ulaw(down,2)).decode()
        await self.ws.send_json({'event':'media','streamSid':self.stream,'media':{'payload':encoded}})
    async def mark_ready(self,peer,version):
        name='jv-'+str(version)
        self.pending_marks={name:(peer,version)}
        await self.ws.send_json({'event':'mark','streamSid':self.stream,'mark':{'name':name}})
    async def acknowledge(self,name):
        pending=self.pending_marks.pop(name,None)
        if pending:
            peer,version=pending
            if peer.config_version==version and not peer.socket.closed:
                await peer.socket.send_json({'type':'ready','version':version,'confirmation':'provider_playback'})
    async def close(self,**kwargs):await self.ws.close()

class PhoneGateway:
    def __init__(self,state,config):
        from twilio.request_validator import RequestValidator
        self.validator=RequestValidator(config.secret)
        self.state,self.config=state,config
        self.calls={}
        self.outgoing_lock=asyncio.Lock()

    async def rest(self,method,path,form=None):
        url=f'https://api.twilio.com/2010-04-01/Accounts/{self.config.account}/'+path
        async with ClientSession(timeout=ClientTimeout(total=15),auth=BasicAuth(self.config.account,self.config.secret)) as session:
            async with session.request(method,url,data=form) as response:
                if response.status>=300:raise ValueError(f'Telefonia respondeu HTTP {response.status}')
                return await response.json()

    def twiml(self,call):
        root=Element('Response');connect=SubElement(root,'Connect')
        stream=SubElement(connect,'Stream',{'url':self.config.public_url.replace('https:','wss:',1)+'/telephony/media'})
        SubElement(stream,'Parameter',{'name':'ticket','value':call.ticket})
        return tostring(root,encoding='unicode')

    async def outgoing(self,request):
        data=await request.json();room=data.get('room','');number=data.get('number','')
        if number not in self.config.allowed:raise ValueError('Número não habilitado pelo administrador da central')
        async with self.outgoing_lock:
            if room in self.calls:raise ValueError('Já existe ligação nesta sala')
            peers=self.state.rooms.get(room,[])
            version=data.get('configuration_version',0)
            if len(peers)!=1:raise ValueError('Aguarde a conexão à sala antes de telefonar')
            if type(version) is int and 1<=version<=2147483647:
                peers[0].accepted=True
                if peers[0].config_version != version:
                    peers[0].config_version=version
                    peers[0].pending_ready=version
            if not peers[0].accepted:raise ValueError('Entre na sala e toque Aceitar antes de telefonar')
            call=PhoneCall(room,secrets.token_urlsafe(32),created=time.monotonic());self.calls[room]=call
        try:
            result=await self.rest('POST','Calls.json',{'To':number,'From':self.config.from_number,
                'Twiml':self.twiml(call),'Timeout':'20','TimeLimit':str(self.config.time_limit),
                'StatusCallback':self.config.public_url+'/telephony/status'})
            call.sid=result['sid']
            if call.cancelled:
                await self.stop_provider(call.sid)
                return web.json_response({'error':'Ligação cancelada'},status=409)
            return web.json_response({'call_id':call.sid,'status':'dialing','carrier_interception':False})
        except Exception:
            self.calls.pop(room,None)
            raise

    async def stop_provider(self,sid):
        current=await self.rest('GET',f'Calls/{sid}.json')
        status=current.get('status')
        if status in ('completed','busy','failed','no-answer','canceled'):return
        await self.rest('POST',f'Calls/{sid}.json',{'Status':'canceled' if status in ('queued','ringing') else 'completed'})

    def start_timeout(self,call):
        async def expire():
            await asyncio.sleep(20)
            peers=self.state.rooms.get(call.room,[])
            if peers and all(p.accepted for p in peers):
                await asyncio.sleep(max(0,self.config.time_limit-20))
            if self.calls.get(call.room) is call:
                from app import send_event
                recipients=list(self.state.rooms.get(call.room,[]))
                try:await self.hangup(call.room)
                finally:await send_event(recipients,'ended')
        call.timeout_task=asyncio.create_task(expire())

    async def hangup(self,room):
        call=self.calls.get(room)
        if not call:return
        call.cancelled=True
        if call.timeout_task and call.timeout_task is not asyncio.current_task():call.timeout_task.cancel()
        if call.socket is not None:await call.socket.close()
        try:
            if call.sid:await self.stop_provider(call.sid)
        finally:
            self.calls.pop(room,None)

    async def signed(self,request,params):
        signature=request.headers.get('X-Twilio-Signature','')
        https=self.config.public_url+request.path_qs
        urls=[https]
        if request.path.endswith('/media'):
            urls.extend([https+'/',https.replace('https:','wss:',1),https.replace('https:','wss:',1)+'/'])
        return any(self.validator.validate(url,params,signature) for url in urls)

    async def incoming(self,request):
        params=await request.post()
        if not await self.signed(request,params):return web.Response(status=403)
        if params.get('AccountSid')!=self.config.account or params.get('To')!=self.config.from_number:return web.Response(status=403)
        room=self.config.incoming_room
        peers=self.state.rooms.get(room,[])
        if len(peers)!=1 or room in self.calls:
            return web.Response(text='<Response><Reject reason="busy"/></Response>',content_type='application/xml')
        call=PhoneCall(room,secrets.token_urlsafe(32),sid=params.get('CallSid',''),created=time.monotonic())
        self.calls[room]=call
        return web.Response(text=self.twiml(call),content_type='application/xml')

    async def status(self,request):
        params=await request.post()
        if not await self.signed(request,params) or params.get('AccountSid')!=self.config.account:return web.Response(status=403)
        if params.get('CallStatus') in ('completed','busy','failed','no-answer','canceled'):
            call=next((c for c in self.calls.values() if c.sid==params.get('CallSid')),None)
            if call:
                from app import send_event
                peers=list(self.state.rooms.get(call.room,[]))
                await self.hangup(call.room)
                await send_event(peers,'ended')
        return web.Response(status=204)

    async def media(self,request):
        if not await self.signed(request,{}):return web.Response(status=403)
        ws=web.WebSocketResponse(max_msg_size=8192,heartbeat=20)
        await ws.prepare(request)
        call=None;peer=None;peers=[];up_state=None
        from app import Peer,send_event
        try:
            deadline=time.monotonic()+8
            while True:
                event=await asyncio.wait_for(ws.receive_json(),max(.01,deadline-time.monotonic()))
                if event.get('event')=='start':break
            start=event['start'];ticket=start.get('customParameters',{}).get('ticket','')
            call=next((c for c in self.calls.values() if secrets.compare_digest(c.ticket,ticket)),None)
            if not call or call.cancelled or start.get('accountSid')!=self.config.account or (call.sid and start.get('callSid')!=call.sid):
                await ws.close(code=1008);return ws
            if not call.sid:call.sid=start.get('callSid','')
            fmt=start.get('mediaFormat',{})
            if fmt.get('encoding')!='audio/x-mulaw' or fmt.get('sampleRate')!=8000 or fmt.get('channels')!=1:
                await ws.close(code=1008);return ws
            peers=self.state.rooms.get(call.room,[])
            if len(peers)!=1:await ws.close(code=1008);return ws
            call.socket=PhoneSocket(ws,start['streamSid']);peer=Peer(call.socket,accepted=True);peers.append(peer)
            self.start_timeout(call)
            await send_event(peers,'ringing')
            if all(p.accepted for p in peers):await send_event(peers,'active')
            async for message in ws:
                if message.type!=WSMsgType.TEXT:continue
                import json
                event=json.loads(message.data)
                if event.get('event')=='stop':break
                if event.get('event')=='mark':
                    await call.socket.acknowledge(event.get('mark',{}).get('name',''))
                if event.get('event')=='media' and all(p.accepted for p in peers):
                    packet=base64.b64decode(event['media']['payload'],validate=True)
                    if len(packet)>3200:raise ValueError('Frame telefônico muito grande')
                    pcm=audioop.ulaw2lin(packet,2)
                    up,up_state=audioop.ratecv(pcm,2,1,8000,16000,up_state)
                    for other in list(peers):
                        if other is not peer:
                            for p in range(0,len(up),640):await asyncio.wait_for(other.socket.send_bytes(up[p:p+640]),.25)
        except (ValueError,KeyError,asyncio.TimeoutError,ConnectionError):
            await ws.close(code=1008)
        finally:
            if peer in peers:peers.remove(peer)
            if peers:await send_event(peers,'ended')
            if call:
                try:await self.hangup(call.room)
                except Exception:pass # provider hard TimeLimit bounds an outage
        return ws

    async def close(self):
        await asyncio.gather(*(self.hangup(room) for room in list(self.calls)),return_exceptions=True)

def install(app,config):
    gateway=PhoneGateway(app['state'],config);app['phone']=gateway
    app.add_routes([web.post('/api/phone/outgoing',gateway.outgoing),web.post('/telephony/incoming',gateway.incoming),web.post('/telephony/status',gateway.status),web.get('/telephony/media',gateway.media)])
    return gateway
