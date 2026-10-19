#!/usr/bin/env bash
# Optional installer; run inside a Python 3.12 virtual environment.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import sys
if sys.version_info[:2] != (3,12):
    raise SystemExit('Use Python 3.12 para as dependências validadas desta versão.')
if sys.prefix==sys.base_prefix:
    raise SystemExit('Crie/ative um ambiente virtual antes de instalar o motor.')
PY
mkdir -p .vendor .models/checkpoints_v2/converter
if [ ! -d .vendor/OpenVoice/.git ]; then
    git clone https://github.com/myshell-ai/OpenVoice.git .vendor/OpenVoice
fi
git -C .vendor/OpenVoice checkout 74a1d147b17a8c3092dd5430504bd83ef6c7eb23
python3 -m pip install 'torch==2.5.1' 'torchaudio==2.5.1' --index-url https://download.pytorch.org/whl/cpu
python3 -m pip install -r server/requirements-openvoice.txt
python3 -m pip install --no-deps 'wavmark==0.0.3' -e .vendor/OpenVoice
python3 - <<'PY'
from pathlib import Path
import urllib.request, hashlib
revision='f36e7edfe1684461a8343844af60babc2efbb727'
root=Path('.models/checkpoints_v2/converter')
hashes={
 'config.json':'9dfff60350b8c63f2c664efd92a61b2516efb22671466960f0e5dfebd881fa47',
 'checkpoint.pth':'9652c27e92b6b2a91632590ac9962ef7ae2b712e5c5b7f4c34ec55ee2b37ab9e',
}
for name,expected in hashes.items():
    destination=root/name
    if destination.exists():
        if hashlib.sha256(destination.read_bytes()).hexdigest()!=expected:
            raise SystemExit(f'Hash incorreto em {name}; modelo recusado.')
        print(name,'verificado')
        continue
    temporary=destination.with_suffix(destination.suffix+'.part')
    url=f'https://huggingface.co/myshell-ai/OpenVoiceV2/resolve/{revision}/converter/{name}'
    print('Baixando',name,flush=True)
    urllib.request.urlretrieve(url,temporary)
    if hashlib.sha256(temporary.read_bytes()).hexdigest()!=expected:
        temporary.unlink()
        raise SystemExit(f'Hash incorreto em {name}; download recusado.')
    temporary.replace(destination)
print('Modelos baixados. A inicialização do servidor ainda precisa validar o carregamento.')
PY
python3 -c "from openvoice.api import ToneColorConverter; print('Importação OpenVoice concluída')"
printf '%s\n' 'Execute python3 server/launch.py neste mesmo ambiente virtual.'
