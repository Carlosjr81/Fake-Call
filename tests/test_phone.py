"""Protocol tests against a fake provider: no live call and no provider expense."""
import asyncio,base64,io,json,struct,sys,tempfile,unittest
from pathlib import Path
from aiohttp import ClientSession
from aiohttp.test_utils import TestServer
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from app import create_app
from phone import PhoneConfig
TOKEN='local-testing-key-1234567890123456'
SID='AC'+'a'*32;CALL='CA'+'c'*32;STREAM='MZ'+'d'*32

class PhoneTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.config=PhoneConfig(SID,'provider-test-secret-1234567890','+15550000001','https://example.test','incoming-room',{'+15550000002'})
        self.app=create_app(TOKEN,Path(self.tmp.name),phone_config=self.config)
        self.phone=self.app['phone'];self.rest_calls=[]
        async def fake_rest(method,path,form=None):
            self.rest_calls.append((method,path,form))
            if method=='GET':return {'status':'ringing'}
            return {'sid':CALL}
        self.phone.rest=fake_rest
        self.server=TestServer(self.app);await self.server.start_server()
        self.client=ClientSession(headers={'Authorization':'Bearer '+TOKEN});self.sockets=[]
    async def asyncTearDown(self):
        for s in self.sockets:await s.close()
        await self.client.close();await self.server.close();self.tmp.cleanup()
    def signature(self,path,params={}):return self.phone.validator.compute_signature('https://example.test'+path,params)
    async def local(self,room):
        s=await self.client.ws_connect(self.server.make_url('/signal/'+room));self.sockets.append(s)
        self.assertEqual('waiting',(await s.receive_json(timeout=2))['type']);return s
    async def test_phone_disabled_by_default(self):
        import os
        from unittest.mock import patch
        with patch.dict(os.environ,{'ENABLE_TELEPHONY':'false'}):self.assertIsNone(PhoneConfig.from_env())
    async def test_reject_unlisted_number_and_missing_signature(self):
        response=await self.client.post(self.server.make_url('/api/phone/outgoing'),json={'room':'testing-room','number':'+15559999999'})
        self.assertEqual(400,response.status);self.assertEqual([],self.rest_calls)
        response=await self.client.post(self.server.make_url('/telephony/incoming'),data={})
        self.assertEqual(403,response.status)
    async def test_outgoing_audio_in_both_directions_and_hangup(self):
        a=await self.local('testing-room');await a.send_json({'type':'accept'});await asyncio.sleep(.01)
        response=await self.client.post(self.server.make_url('/api/phone/outgoing'),json={'room':'testing-room','number':'+15550000002'})
        self.assertEqual(200,response.status)
        call=self.phone.calls['testing-room']
        path='/telephony/media'
        remote=await self.client.ws_connect(self.server.make_url(path),headers={'X-Twilio-Signature':self.signature(path)});self.sockets.append(remote)
        await remote.send_json({'event':'start','start':{'streamSid':STREAM,'accountSid':SID,'callSid':CALL,'customParameters':{'ticket':call.ticket},'mediaFormat':{'encoding':'audio/x-mulaw','sampleRate':8000,'channels':1}}})
        self.assertEqual('ringing',(await a.receive_json(timeout=2))['type'])
        self.assertEqual('active',(await a.receive_json(timeout=2))['type'])
        await a.send_bytes(struct.pack('<h',3000)*320)
        message=await remote.receive_json(timeout=2)
        self.assertEqual('media',message['event']);self.assertEqual(STREAM,message['streamSid'])
        self.assertEqual(160,len(base64.b64decode(message['media']['payload'])))
        await remote.send_json({'event':'media','media':{'payload':message['media']['payload']}})
        received=(await a.receive(timeout=2)).data
        self.assertIsInstance(received,bytes);self.assertTrue(600<=len(received)<=640)
        self.assertGreater(abs(struct.unpack_from('<h',received)[0]),2000)
        await a.close();await asyncio.sleep(.03)
        self.assertTrue(any(form and form.get('Status')=='canceled' for _,_,form in self.rest_calls))
    async def test_ready_beep_waits_for_processed_version_and_provider_playback(self):
        a=await self.local('testing-room')
        await a.send_json({'type':'configure','version':7})
        response=await self.client.post(self.server.make_url('/api/phone/outgoing'),json={'room':'testing-room','number':'+15550000002','configuration_version':7})
        self.assertEqual(200,response.status)
        call=self.phone.calls['testing-room'];path='/telephony/media'
        remote=await self.client.ws_connect(self.server.make_url(path),headers={'X-Twilio-Signature':self.signature(path)});self.sockets.append(remote)
        await remote.send_json({'event':'start','start':{'streamSid':STREAM,'accountSid':SID,'callSid':CALL,'customParameters':{'ticket':call.ticket},'mediaFormat':{'encoding':'audio/x-mulaw','sampleRate':8000,'channels':1}}})
        self.assertEqual('ringing',(await a.receive_json(timeout=2))['type'])
        self.assertEqual('active',(await a.receive_json(timeout=2))['type'])
        # Stale processed audio must be discarded after a new configuration.
        await a.send_bytes(b'JV'+struct.pack('>I',6)+struct.pack('<h',1000)*320)
        await a.send_bytes(b'JV'+struct.pack('>I',7)+struct.pack('<h',3000)*320)
        media=await remote.receive_json(timeout=2);self.assertEqual('media',media['event'])
        mark=await remote.receive_json(timeout=2);self.assertEqual('mark',mark['event'])
        with self.assertRaises(asyncio.TimeoutError):await a.receive_json(timeout=.02)
        # Only an actual provider mark acknowledgement unlocks readiness.
        await remote.send_json({'event':'mark','mark':mark['mark']})
        ready=await a.receive_json(timeout=2)
        self.assertEqual('ready',ready['type']);self.assertEqual(7,ready['version'])
        self.assertEqual('provider_playback',ready['confirmation'])

    async def test_incoming_requires_waiting_app_and_valid_provider(self):
        params={'AccountSid':SID,'CallSid':CALL,'To':'+15550000001'};path='/telephony/incoming';headers={'X-Twilio-Signature':self.signature(path,params)}
        r=await self.client.post(self.server.make_url(path),data=params,headers=headers)
        self.assertIn('Reject',await r.text())
        await self.local('incoming-room')
        r=await self.client.post(self.server.make_url(path),data=params,headers=headers)
        self.assertEqual(200,r.status);xml=await r.text();self.assertIn('<Connect>',xml);self.assertIn('wss://example.test/telephony/media',xml)
        self.assertEqual([],self.rest_calls)
    async def test_no_answer_status_releases_room(self):
        a=await self.local('testing-room');await a.send_json({'type':'accept'});await asyncio.sleep(.01)
        r=await self.client.post(self.server.make_url('/api/phone/outgoing'),json={'room':'testing-room','number':'+15550000002'})
        self.assertEqual(200,r.status)
        params={'AccountSid':SID,'CallSid':CALL,'CallStatus':'no-answer'};path='/telephony/status'
        r=await self.client.post(self.server.make_url(path),data=params,headers={'X-Twilio-Signature':self.signature(path,params)})
        self.assertEqual(204,r.status);self.assertNotIn('testing-room',self.phone.calls)
        self.assertEqual('ended',(await a.receive_json(timeout=2))['type'])

    async def test_websocket_invalid_signature_rejected(self):
        from aiohttp import WSServerHandshakeError
        with self.assertRaises(WSServerHandshakeError):await self.client.ws_connect(self.server.make_url('/telephony/media'))

if __name__=='__main__':unittest.main(verbosity=2)
