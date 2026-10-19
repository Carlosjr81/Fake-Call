# Jarvis Voice — Android 0.1.0 / servidor 0.2.0

Aplicativo nativo para efeitos de voz, ambientes, gravações e chamadas pelo próprio app.
**É uma versão de desenvolvimento. A troca de voz durante ligações do chip não foi implementada.**

## O que esta versão entrega

| Função | Situação real |
|---|---|
| Microfone, efeitos original/grave/agudo/robô/rádio | Implementado; processamento verificado com sinais de teste |
| Ambientes chuva/trânsito/ondas sintetizados | Implementado; não são gravações realistas |
| Importar ambiente real | WAV PCM16 mono/estéreo, até 2 min; mistura e repetição local |
| Gravar, ouvir e exportar | WAV PCM16, 16 kHz; até 2 min |
| Chamada entre dois apps | Implementada via servidor privado; protocolo testado com dois clientes |
| Telefonar a um número comum | Ponte Twilio implementada e testada com provedor simulado; depende de implantação, credenciais e saldo |
| Receber chamadas na central | Webhook implementado; app precisa estar aberto aguardando na sala configurada |
| Usar o número móvel atual para recebimento | Exige encaminhamento ou portabilidade que a operadora/provedor aceite; não configurado |
| Cadastrar referência de voz própria/autorizada | Implementado no servidor |
| Converter gravação para timbre de referência | OpenVoice V2 carregado e conversão real executada; identidade/naturalidade não avaliadas |
| Clonar timbre ao vivo em chamadas | **Não implementado**. Chamadas enviam os efeitos locais |
| Modificar ligação normal do chip, WhatsApp ou outro discador | **Não disponível por APIs públicas usadas neste projeto** |
| Histórico | Somente sessões do Jarvis; não lê histórico da operadora |

## Seleção durante a chamada e bip

Na preparação da chamada, o app abre o menu de voz e ambiente. Ele também pode ser aberto pelo botão **Voz e ambiente · alterar agora**. Ao aplicar, cada bloco processado recebe a versão dos ajustes. O servidor descarta blocos antigos. Na central, o bip espera a confirmação `mark` do provedor após a reprodução de um bloco dessa versão. Entre dois apps, o bip confirma envio na rota ativa, não reprodução no outro aparelho.

O bip é gerado localmente; o microfone é silenciado por 240 ms para reduzir sua retransmissão. A confirmação não mede se a pessoa ouviu, volume físico, qualidade perceptual ou atraso real de rede. Não há prazo de conexão garantido.

## Instalar e testar no Android

1. Instale o APK de desenvolvimento em Android 11 ou superior. Se necessário, permita a instalação pelo aplicativo usado para abrir o arquivo.
2. Abra **Jarvis Voice → Estúdio** e conceda microfone.
3. Escolha efeito e ambiente, toque **Gravar**, fale e toque **Parar e salvar**.
4. Toque **Ouvir resultado**. **Exportar WAV** abre o seletor de destino do Android.
5. Para monitorar ao vivo, use fones. O monitoramento fica desligado inicialmente.

O resultado e a fala original ficam no armazenamento privado do app. A chave do servidor e o token opcional do Codespaces ficam apenas na memória da sessão. Não há publicidade nem telemetria neste código.

## Servidor

Consulte [SERVIDOR.md](docs/SERVIDOR.md). Há configuração para Render, GitHub Codespaces e servidor Linux próprio. Veja também [RENDER.md](docs/RENDER.md). **Nenhum servidor foi iniciado na sua conta por esta entrega.**

Para duas pessoas: configure a mesma origem HTTPS, chave e sala em **Chamadas**; ambas entram e tocam **Aceitar chamada**. O app precisa ficar aberto. Sair ou perder o foco de áudio encerra a sessão. Não há notificações de chamadas em segundo plano nesta versão.

Para ligar a números telefônicos: [TELEFONIA.md](docs/TELEFONIA.md). Essa central substitui o caminho da chamada; não altera o áudio de uma ligação de chip já em andamento.

## Compilar

Abra `android/` no Android Studio, com SDK 35 e JDK 17, ou execute:

```bash
cd android
bash gradlew assembleDebug lintDebug
```

O APK fica em `android/app/build/outputs/apk/debug/app-debug.apk`. O APK fornecido é assinado para desenvolvimento, não publicado na Play Store.

## Verificar

```bash
python3 -m pip install -r server/requirements-phone.txt
python3 -m unittest discover -s tests -v
bash tests/run-audio.sh
```

Há 15 testes de servidor/telefonia simulada/cache e 8 verificações do processamento de áudio. Os testes de telefonia substituem explicitamente o provedor; não efetuam ligações. O teste de conversão substitui explicitamente o motor de IA; não prova clonagem neural.

O modelo OpenVoice V2 foi carregado e converteu áudio real em teste local. Não houve teste em um telefone físico, ligação real ou operadora. A compilação não valida microfone, eco, Bluetooth ou latência em aparelho.

## Pesquisa e limites

[Pesquisa com evidências](docs/PESQUISA.md) e [verificações](docs/VALIDACAO.md). A pesquisa cobre alternativas relevantes; não é inventário de todos os apps/serviços existentes.

## Medição do motor

A referência usada foi a referência sintética autorizada de Carlos Jr., recuperada do perfil VoiceStudio. Não é uma nova gravação natural. Depois do aquecimento, 6 s de entrada levaram aproximadamente 2,84–2,96 s para converter. Em uma experiência com embeddings de origem/destino em cache, blocos de 1 s variaram de 0,85 a 2,10 s. Não houve folga estável para conversa contínua. Os dados estão em [OPENVOICE.md](docs/OPENVOICE.md). Clonagem ao vivo não foi habilitada.

O APK é o mesmo binário de desenvolvimento já compilado. As alterações desta revisão estão no servidor, instalador, diagnóstico e configuração de implantação.
