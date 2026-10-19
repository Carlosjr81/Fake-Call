# Voz modificada em chamadas a números comuns

## O caminho implementado

Microfone do Android → efeito local e ambiente → WSS → servidor Jarvis → Media Streams da Twilio → número telefônico.

A voz da outra pessoa retorna pelo mesmo caminho. A ponte converte PCM16 mono 16 kHz do app em μ-law 8 kHz da telefonia. Essa redução limita a faixa de áudio; não é som de estúdio.

Isso **não modifica a ligação normal do chip**. A ligação precisa passar pela central. Tornar o Jarvis discador padrão, rodar IA localmente ou mover a IA para um servidor não concede acesso ao caminho de áudio do modem.

## Estado da entrega

Implementados: origem de chamada, conexão do fluxo bidirecional, validação de assinatura, conversão dos codecs, recebimento via webhook, encerramento pelo app e limite de duração. Testados **com provedor simulado**.

Não executados: cadastro/compra de número, depósito de saldo, implantação pública, ligação real, portabilidade ou encaminhamento. Não há credenciais de telefonia na entrega. Nenhuma chamada foi feita e nenhum serviço foi contratado.

## Preparar a central

1. Usar uma conta Twilio Programmable Voice com número capaz de fazer/receber chamadas e permissão para os destinos desejados. Disponibilidade de número, apresentação de identificador e encaminhamento dependem do provedor e da operadora.
2. Instalar `server/requirements-phone.txt` no servidor.
3. Configurar somente no servidor, como variáveis de ambiente:
   - `ENABLE_TELEPHONY=true`
   - `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` e `TWILIO_FROM_NUMBER`
   - `JARVIS_PUBLIC_URL=https://origem-publica`
   - `JARVIS_INCOMING_ROOM=telefone-pessoal`
   - `JARVIS_ALLOWED_NUMBERS` com os destinos E.164 habilitados, separados por vírgulas
   - `PHONE_TIME_LIMIT=120`, entre 30 e 300 segundos
4. Para chamadas recebidas, configurar o webhook POST de voz do número como `https://origem-publica/telephony/incoming`.
5. Iniciar o servidor. Para chamadas a números comuns, ele precisa estar alcançável pelo provedor. A API exige a chave própria e os webhooks exigem assinatura Twilio válida.

No Android, configure a origem e chave. O menu de voz e ambiente abre durante a preparação, antes do primeiro áudio modificado. O botão de alteração funciona durante a conversa. O microfone é silenciado por 240 ms durante o bip local. O bip só é liberado quando o provedor confirma a reprodução de um bloco da versão atual dos ajustes; uma confirmação antiga não libera o bip. Em Chamadas, entre numa sala, toque Aceitar, digite o destino no formato `+55DDDnúmero` e toque **Ligar pela central configurada**. Para aguardar chamadas recebidas, use a sala configurada em `JARVIS_INCOMING_ROOM` e mantenha o app aberto.

A outra pessoa atende no telefone comum; não precisa instalar o Jarvis quando a central está habilitada. O chamador do Jarvis usa internet. Para quem disca para o número móvel atual chegar nessa central, é necessário encaminhamento ou portabilidade **quando disponível**, que não foi configurado aqui.

A central é desabilitada por padrão. Telefonia pode cobrar número, minutos e fluxo de mídia, além da infraestrutura; a tarifa atual não foi consultada e não foi aprovada despesa. Os segredos permanecem no servidor.

Os efeitos de voz funcionam ao vivo nesse caminho; **clonagem neural ao vivo ainda não foi implementada**. O OpenVoice desta versão converte gravações.

## Encerramento

Sair do app encerra a sessão. O servidor solicita o encerramento da chamada no provedor; em caso de falha de rede, o limite de duração configurado é a contenção final. Não há suporte a emergência, chamadas em segundo plano ou serviço de telefonia contínuo nesta versão.

Fontes:
- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/reference/android/telecom/InCallService
- https://www.twilio.com/docs/voice/media-streams
- https://www.twilio.com/docs/voice/media-streams/websocket-messages
- https://www.twilio.com/docs/usage/webhooks/webhooks-security
