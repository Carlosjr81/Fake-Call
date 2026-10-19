# Verificações da entrega

## Executado

- Compilação Android nativa com JDK 17, Gradle 8.11.1, AGP 8.9.2 e SDK 35.
- Análise `lintDebug`, sem erros; permanecem avisos de localização de textos e regra de extração de dados/backup. Sem supressão de erros de permissão ou compatibilidade.
- 14 testes assíncronos de servidor: autenticação, ausência explícita do motor, cadastro/exclusão de referência, formatos inválidos, capacidade e isolamento de salas, aceitação dos dois lados, áudio bidirecional, assinatura de webhook, restrição de destinos, origem/encerramento simulado e liberação da sala após não atendimento.
- O teste da confirmação de prontidão enviou áudio de uma configuração antiga e da atual. O antigo foi descartado. Não houve `ready` antes do retorno de `mark`; o retorno correto liberou a versão atual.
- 8 verificações Java do áudio: preservação do original, mudança de frequência dos dois efeitos de altura, repetição/volume de ambiente importado, saturação sem wrap-around, ida e volta WAV, rejeição de cabeçalho inválido e conversão de estéreo 48 kHz para mono 16 kHz.
- As frequências dominantes dos sinais de teste de 220 Hz foram 176 Hz e 270 Hz nos efeitos grave/agudo. Isso prova mudança de altura, não naturalidade ou imitação de pessoa.
- Conferência da assinatura do APK e de seus metadados, conforme registro de empacotamento.

## Simulado de forma explícita

- O provedor de telefonia nos testes é um stub local. Nenhum número real foi chamado.
- Um teste do contrato de conversão usa um motor fictício que devolve o próprio WAV. Isso verifica a integração HTTP, não o OpenVoice.
- A confirmação de reprodução do provedor é simulada nos testes. O comportamento do protocolo foi confrontado com a documentação; não houve confirmação em uma ligação real.

## Ainda não verificado

- Instalação e uso em Android físico; microfone, eco, fones, Bluetooth e volume do bip.
- Naturalidade e fidelidade de identidade da voz; a conversão neural já foi executada, mas a qualidade perceptual não foi avaliada.
- Latência em 4G/5G/Wi-Fi e sob carga de um servidor externo.
- Serviço contínuo em Codespaces ou outra hospedagem.
- Credenciais, saldo, custos, número e permissões de telefonia; portabilidade/encaminhamento do número móvel atual.
- Uso em segundo plano, notificações de chamada e histórico da operadora: não implementados.

Não houve publicação na Play Store nem alteração no Treino Livre. O código foi preparado para publicação no repositório próprio Carlosjr81/Fake-Call, com workflow de compilação e verificação.

## Revisão do servidor 0.2.0

- Os 14 testes HTTP/WebSocket/telefonia simulada passaram novamente, incluindo prontidão pública mínima em `/healthz` e proteção de `/api/health`.
- OpenVoice V2 carregado de checkpoint oficial fixado; conversão real executada com WAV mono 16 kHz. Confira [OPENVOICE.md](OPENVOICE.md).
- O adaptador reutiliza embeddings de referências sem extrair novamente o timbre alvo em cada gravação. As inferências permanecem serializadas.
- `render.yaml` validado contra o esquema JSON público atual. A validação de plataforma e o build do Docker não foram executados.
- Render conectado e workspace My Workspace identificado pelo usuário. Nenhum serviço foi criado. O repositório próprio Carlosjr81/Fake-Call foi criado para esta implantação.
- O APK Android permanece o binário compilado e assinado da entrega anterior. A compilação Android também passou nesta sessão antes da seleção do workspace; esse build temporário não substitui o APK entregue.

- A suíte completa atual tem 15 testes: os 14 testes de integração anteriores e um teste do cache que verifica reutilização, invalidação, isolamento e limite de memória. Todos passaram.
- Comando de início executado em processo real: prontidão pública, autenticação e indicação correta de motor/provedor ausentes passaram. A imagem Docker não foi compilada neste ambiente.

## Implantação remota concluída

Em 19/10/2026, o repositório próprio foi criado e o servidor 0.2.0 implantado no Render, plano free, região Virginia, sem motor neural nem telefonia. O teste HTTPS/WSS externo confirmou prontidão, proteção da API, áudio em dois sentidos entre dois clientes, descarte de versão antiga e confirmação da versão atual. A ida do bloco e a confirmação levaram 0,077 s nessa medição entre ambiente de teste e servidor; não é latência de uma chamada em celular. O teste e o protocolo estão em `evidencias/render-live.json`. Não foram feitas chamadas telefônicas. As notas anteriores que dizem não haver implantação descrevem as entregas anteriores.
