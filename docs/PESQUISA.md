# Pesquisa, engenharia reversa e escolhas

Pesquisa direcionada em fontes primárias; não é possível afirmar que abrange todos os aplicativos e serviços existentes. Foram examinadas as alternativas abaixo. Não houve inspeção dinâmica de APK proprietário ou acesso a servidores privados desses produtos.

| Alternativa / conclusão | Evidência | Estado e limite |
|---|---|---|
| Android prioriza o áudio da chamada e restringe sua captura a casos especiais | https://developer.android.com/media/platform/sharing-audio-input | Documentado; não foi feito teste no telefone do usuário |
| O papel de discador padrão gerencia interface e controle de chamadas | https://developer.android.com/reference/android/telecom/InCallService | Documentado; não fornece neste projeto um canal de substituição do áudio do modem |
| Redirecionamento de chamada não equivale a processar seu áudio | https://developer.android.com/reference/android/telecom/CallRedirectionService | Documentação da API; não implementado como falsa solução de áudio |
| Treino Livre não possui servidor de IA já reutilizável no repositório observado | https://github.com/Carlosjr81/Treino-Livre/blob/main/CLAUDE.md | Observado no commit 88bc2b2f682cfae6e76906cd43f779a03dd1e08d; não é inventário dos demais projetos da conta |
| Piper/ONNX no Treino Livre executa narração no aparelho | Mesma documentação, seção “O resumo vira vídeo para o story” | Observado; síntese por texto não é clonagem ou conversão em tempo real |
| GitHub Pages hospeda conteúdo estático | https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages | Documentado; não executa o servidor Python |
| Codespaces pode executar um ambiente de desenvolvimento com porta encaminhada | https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace | Documentado; configuração incluída, conta/instância/franquia não verificadas |
| w-okada/VCClient é uma opção de conversão em tempo real | https://github.com/w-okada/voice-changer | README e arquivos públicos do cliente examinados; não executado nem integrado nesta entrega |
| Seed-VC converte por referência e possui modo em tempo real | https://github.com/Plachtaa/seed-vc | O autor recomenda GPU; os tempos publicados não são medição deste projeto. Não incluído no APK |
| RVC é alternativa de conversão com modelos treinados | https://github.com/RVC-Project/Retrieval-based-Voice-Conversion-WebUI | Repositório consultado; não treinado nem medido em Android |
| OpenVoice fornece extração de timbre e conversão por referência | https://github.com/myshell-ai/OpenVoice/blob/main/openvoice/api.py | Assinaturas verificadas; adaptador escrito; inferência real e fidelidade não testadas |
| Modelos oficiais OpenVoice V2 estão disponíveis separadamente | https://huggingface.co/myshell-ai/OpenVoiceV2/tree/main/converter | Código e modelos são dependências distintas; não confundir baixar código com motor pronto |
| Voicemod fornece modificação de voz e API de controle | https://control-api.voicemod.net/ | API controla o aplicativo Voicemod; não comprova inserção de áudio em ligação SIM Android |
| Funcalls oferece chamadas pela internet com efeitos e ambientes | https://www.funcalls.com/voice-changer/ | Declaração pública do fabricante; não foi medida a implementação interna |
| MagicCall anuncia efeitos e ambientes nas chamadas | https://apps.apple.com/us/app/magiccall-voice-changer-app/id1324524338 | Referência de comportamento anunciado; não auditado nem usado como dependência |
| Twilio aceita áudio bidirecional nas chamadas controladas pelo serviço | https://www.twilio.com/docs/voice/media-streams | Ponte escrita e testada com provedor simulado; conta e chamada real pendentes |
| O evento mark confirma a reprodução de mídia enviada | https://www.twilio.com/docs/voice/media-streams/websocket-messages | Usado para o bip da ponte; não mede se a pessoa ouviu ou a qualidade da voz |
| Asterisk AudioSocket é outra forma de inserir e receber PCM em uma central | https://docs.asterisk.org/Asterisk_23_Documentation/API_Documentation/Dialplan_Applications/AudioSocket/ | Alternativa documentada; não implementada aqui. Conexão à rede telefônica é uma dependência adicional |

## REA realmente executado

Foi analisado um recorte original de 6.846 bytes do transporte MQTT do Treino Livre, extraído do `index.html` do commit observado, começando no marcador “O retransmissor ao vivo (MQTT por WebSocket)”. O arquivo extraído foi entregue no pacote de evidências original; não integra o código executável deste repositório.

- CLI efetiva: `rea-agents@4.1.0`, rota de análise estática indicada pela habilidade para divergência entre PID e procfs neste ambiente Linux.
- Resultado: `ev_62ff35ce7bb1a8eada4bb449d74121cf08f4cf6ec6b46b7b442fd753089a5051`.
- Inventário: 4 nós, incluindo artefato, arquivo JavaScript, módulo e armazenamento.
- Saída bruta de 4,3 MB entregue no pacote de evidências original; o manifesto resumido é `docs/evidencias/rea-treino.json.meta.json`.

O parsing estático terminou sem erro. Ele não executou o transporte, não provou conectividade dos retransmissores públicos e não examinou servidores. A conclusão sobre ausência de backend/motor remoto vem também da leitura da documentação e do inventário do repositório, não de uma suposta prova dinâmica do REA.

## Decisão

O processamento local reduz uma viagem de rede e evita consumir créditos de IA para os efeitos simples. A telefonia comum exige uma rota em que o servidor controle a mídia, implementada como ponte opcional. Clonagem neural continua exigindo modelo de IA, mesmo em outra máquina, e não foi prometida para conversa ao vivo sem teste.
