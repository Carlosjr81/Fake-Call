# Motor externo e servidor

## O que foi encontrado no Treino Livre

Repositório consultado: `Carlosjr81/Treino-Livre`, `main`, commit `88bc2b2f682cfae6e76906cd43f779a03dd1e08d`.

- `CLAUDE.md`, linhas 24–39: site sem backend; a exceção é um retransmissor MQTT do Desafio.
- `CLAUDE.md`, linhas 1176–1202: a narração Piper/ONNX roda no aparelho, com modelo baixado e guardado localmente.
- `.github/workflows/conferir.yml`: executa verificações; não hospeda um servidor de IA.
- `vozes/LEIA-ME.md`: geração de gravações em lote, com APIs externas ou voz do aparelho como alternativa.

Foi reaproveitado o princípio de separar interface, transporte e motor. Não foi encontrado um serviço externo de clonagem já disponível nesse repositório. Nenhum arquivo do Treino Livre foi modificado.

## GitHub Codespaces

Foi incluído `.devcontainer/devcontainer.json`. Ele prepara Python 3.12 e encaminha a porta 8080. **Configuração preparada não significa Codespace criado ou servidor implantado.**

1. Coloque este projeto em um repositório próprio. Abra um Codespace desse repositório.
2. No terminal, execute `python3 server/launch.py`. A chave do aplicativo aparece apenas nesse terminal; digite-a no campo correspondente do Android.
3. Na aba **Ports**, copie a origem `https://...-8080.app.github.dev` para o Android.
4. Uma porta privada exige também o token da sessão do Codespaces no campo opcional do Android. Consulte a documentação oficial de acesso por REST. Não compartilhe esse token nem o coloque no código.
5. Para teste da ponte de telefonia, o provedor precisa alcançar os webhooks. A porta deverá ser pública; a API mantém a própria chave e os webhooks exigem assinatura do provedor. A publicação dessa porta não foi executada.

Codespaces é ambiente de desenvolvimento: depende da sessão, suspensão por inatividade e franquia/cobrança da conta. Não foi verificado saldo da conta e não foi assumida disponibilidade 24 horas. Não se deve executar uma chamada contando com uma sessão suspensa.

O GitHub Pages serve os arquivos estáticos. Ele não executa este servidor Python.

## Linux próprio

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements.txt
python server/launch.py
```

Coloque um proxy HTTPS com certificado confiável na frente da porta 8080, conforme `server/Caddyfile.example`. O app recusa HTTP em rede e não ignora erros de certificado.

## OpenVoice

O adaptador usa `ToneColorConverter.extract_se` para a fala de origem e a referência e `convert` para a conversão. Retém a marcação padrão do motor. O resultado é convertido para WAV mono 16 kHz antes de voltar ao app. O ambiente é misturado **depois** da conversão.

O motor é opcional. Sem ele, `/api/health` retorna `engine_ready=false`, e `/api/convert` retorna 503 com explicação; o app não simula um clone.

Crie e ative um ambiente virtual com Python 3.12 antes da instalação. Para prepará-lo, há o script `server/setup-openvoice.sh`, destinado a Linux/Python 3.12. Ele fixa o código e os modelos oficiais, verifica SHA-256 e instala o conjunto de dependências Python 3.12 usado na validação do motor. O conjunto foi instalado durante os testes; o script atualizado ainda não foi executado do zero em Render. Depois, `server/launch.py` detecta os modelos. A inicialização falha se não conseguir carregar o modelo, em vez de declarar disponibilidade falsa.

O teste local carregou o modelo e converteu áudio, mas blocos curtos não mantiveram velocidade estável de conversa. Não houve medição em Codespaces ou celular. Por isso, esta versão usa o OpenVoice somente em gravações de 1–60 s, com referências de 3–30 s. Há limite de 32 referências e exclusão pela interface.

Fontes:
- https://github.com/Carlosjr81/Treino-Livre/blob/main/CLAUDE.md
- https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages
- https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace
- https://github.com/myshell-ai/OpenVoice
- https://huggingface.co/myshell-ai/OpenVoiceV2

## Render

Veja [RENDER.md](RENDER.md). O Blueprint incluído é para a ponte de áudio com efeitos locais; não carrega o motor neural. `/healthz` informa somente a prontidão HTTP, sem exigir chave. Diagnósticos e operações em `/api/` continuam autenticados.

Em CPU, o servidor usa duas threads PyTorch por padrão, como no ensaio; ajuste `OPENVOICE_THREADS` entre 1 e 8 somente depois de medir a máquina.
