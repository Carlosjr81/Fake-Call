# Dependências e referências

Código próprio desta entrega: licença MIT, em `LICENSE`.

- Android SDK / plataforma Android: componentes de compilação, não incluídos no pacote do projeto.
- OkHttp 4.12.0, Okio e Kotlin: licenças Apache 2.0. Referências: https://github.com/square/okhttp , https://github.com/square/okio , https://github.com/JetBrains/kotlin . O APK inclui seus componentes transitivos.
- aiohttp: Apache 2.0. https://github.com/aio-libs/aiohttp . Instalado no servidor por requisitos.
- Twilio Python: MIT. https://github.com/twilio/twilio-python . Opcional para a ponte; o serviço de telefonia tem termos e cobrança próprios.
- OpenVoice: MIT. https://github.com/myshell-ai/OpenVoice . Não incluído no APK; instalador opcional fixa o commit 74a1d147b17a8c3092dd5430504bd83ef6c7eb23.
- Modelos OpenVoice V2: o repositório oficial informa MIT. https://huggingface.co/myshell-ai/OpenVoiceV2 . Instalador fixa a revisão f36e7edfe1684461a8343844af60babc2efbb727; modelos não incluídos.
- REA: executado somente na investigação; não é dependência do aplicativo.
- Treino Livre: documentação consultada e recorte do transporte preservado como evidência do projeto do próprio usuário. Nenhum trecho desse transporte foi incorporado ao código do novo aplicativo.

A licença de código aberto não elimina custos de infraestrutura, telefonia ou consumo computacional. Conteúdo de voz e ambientes importados permanece sob autorização/licença de seus titulares.
