"""Start the private API. This does not install or claim an AI model is ready."""
import os,secrets,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
runtime=root/'.runtime';runtime.mkdir(mode=0o700,exist_ok=True)
token_file=runtime/'app-token'
if not token_file.exists():
    token_file.write_text(secrets.token_urlsafe(32));token_file.chmod(0o600)
env=os.environ.copy()
env.setdefault('JARVIS_TOKEN',token_file.read_text().strip())
env.setdefault('JARVIS_DATA',str(runtime/'voices'))
model=root/'.models/checkpoints_v2/converter'
if (model/'checkpoint.pth').is_file() and (model/'config.json').is_file():
    env.setdefault('OPENVOICE_CONFIG',str(model/'config.json'))
    env.setdefault('OPENVOICE_CHECKPOINT',str(model/'checkpoint.pth'))
print('Chave do aplicativo (digite no Android; não publique):',env['JARVIS_TOKEN'],flush=True)
if os.getenv('CODESPACE_NAME'):
    print('Servidor:',f"https://{os.environ['CODESPACE_NAME']}-8080.app.github.dev",flush=True)
print('Motor IA:', 'configurado; carregamento será verificado no início' if env.get('OPENVOICE_CONFIG') else 'não instalado',flush=True)
subprocess.run([sys.executable,str(root/'server/app.py')],env=env,check=True)
