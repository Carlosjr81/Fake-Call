import asyncio, base64, io, struct, sys, tempfile, unittest, wave
from pathlib import Path
from aiohttp import ClientSession, WSMsgType, WSServerHandshakeError
from aiohttp.test_utils import TestServer
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from app import create_app, decode_audio

TOKEN='test-key-not-for-production-123456789'

def audio(seconds=3):
    out=io.BytesIO()
    with wave.open(out,'wb') as w:
        w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000)
        w.writeframes(struct.pack('<h',900)*int(16000*seconds))
    return base64.b64encode(out.getvalue()).decode()

class ServerTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.app=create_app(TOKEN,Path(self.temp.name))
        self.server=TestServer(self.app);await self.server.start_server()
        self.client=ClientSession(headers={'Authorization':'Bearer '+TOKEN})
        self.sockets=[]
    async def asyncTearDown(self):
        for s in self.sockets:await s.close()
        await self.client.close();await self.server.close();self.temp.cleanup()
    async def request(self,method,path,**kw):
        response=await self.client.request(method,self.server.make_url(path),**kw)
        return response.status,await response.json()
    async def socket(self,room='testing-room'):
        s=await self.client.ws_connect(self.server.make_url('/signal/'+room));self.sockets.append(s);return s
    async def event(self,s):return (await s.receive_json(timeout=2))['type']
    async def test_auth_health_and_truthful_missing_model(self):
        async with ClientSession() as c:
            r=await c.get(self.server.make_url('/healthz'))
            self.assertEqual(200,r.status)
            self.assertEqual({'status':'ok'},await r.json())
            r=await c.get(self.server.make_url('/api/health'));self.assertEqual(401,r.status)
        status,data=await self.request('GET','/api/health');self.assertEqual(200,status)
        self.assertFalse(data['engine_ready']);self.assertFalse(data['carrier_audio'])
        status,data=await self.request('POST','/api/convert',json={});self.assertEqual(503,status)
    async def test_reference_validation_list_and_deletion(self):
        status,_=await self.request('POST','/api/voices',json={'name':'Minha voz','audio':audio(),'authorized':False});self.assertEqual(400,status)
        status,_=await self.request('POST','/api/voices',json={'name':'Minha voz','audio':audio(1),'authorized':True});self.assertEqual(400,status)
        status,data=await self.request('POST','/api/voices',json={'name':'Minha voz','audio':audio(),'authorized':True});self.assertEqual(201,status)
        identifier=data['id'];self.assertEqual(32,len(identifier))
        status,data=await self.request('GET','/api/voices');self.assertEqual(1,len(data['voices']))
        status,data=await self.request('DELETE','/api/voices/'+identifier);self.assertTrue(data['deleted'])
        status,data=await self.request('GET','/api/voices');self.assertEqual([],data['voices'])
    async def test_bidirectional_audio_requires_both_accept_and_room_capacity(self):
        a=await self.socket();self.assertEqual('waiting',await self.event(a))
        b=await self.socket();self.assertEqual('ringing',await self.event(a));self.assertEqual('ringing',await self.event(b))
        with self.assertRaises(WSServerHandshakeError) as err:await self.socket()
        self.assertEqual(409,err.exception.status)
        # A distinctive packet before consent must not be forwarded.
        await a.send_bytes(b'\x12\x34'*320)
        await a.send_json({'type':'accept'});await b.send_json({'type':'accept'})
        self.assertEqual('active',await self.event(a));self.assertEqual('active',await self.event(b))
        packet=bytes(range(256))*2
        await a.send_bytes(packet);self.assertEqual(packet,(await b.receive(timeout=2)).data)
        reverse=b'\x00\x21'*320
        await b.send_bytes(reverse);self.assertEqual(reverse,(await a.receive(timeout=2)).data)
        await a.close();self.assertEqual('ended',await self.event(b))
    async def test_invalid_frame_is_rejected(self):
        a=await self.socket();await self.event(a)
        await a.send_bytes(b'odd');message=await a.receive(timeout=2)
        self.assertEqual(WSMsgType.CLOSE,message.type)
    async def test_room_isolation(self):
        a=await self.socket('first-room');b=await self.socket('second-room')
        self.assertEqual('waiting',await self.event(a));self.assertEqual('waiting',await self.event(b))
        self.assertEqual(2,len(self.app['state'].rooms))
    async def test_conversion_contract_with_explicit_fake_engine(self):
        class FakeEngine:
            def convert(self,source,reference):return source
        self.app['state'].engine=FakeEngine()
        _,ref=await self.request('POST','/api/voices',json={'name':'Fixture','audio':audio(),'authorized':True})
        status,result=await self.request('POST','/api/convert',json={'voice_id':ref['id'],'audio':audio()})
        self.assertEqual(200,status);self.assertFalse(result['live'])
        self.assertEqual(decode_audio(audio()),base64.b64decode(result['audio']))
    def test_bad_audio_and_traversal(self):
        for s in ['', 'not-base64',base64.b64encode(b'not-wav').decode()]:
            with self.assertRaises(ValueError):decode_audio(s)

if __name__=='__main__':unittest.main(verbosity=2)
