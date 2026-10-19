# Conversão real e limite de velocidade

## Execução observada em 19/10/2026

OpenVoice V2 oficial, commit `74a1d147b17a8c3092dd5430504bd83ef6c7eb23`, pesos da revisão `f36e7edfe1684461a8343844af60babc2efbb727`. Python 3.12, PyTorch/Torchaudio 2.5.1 CPU, duas threads. O motor manteve sua marcação de áudio padrão.

A referência autorizada do perfil Carlos Jr. VoiceStudio era um WAV sintético de 15 s, mono, 24 kHz. Foram recortados dois trechos de 6 s e reamostrados para mono 16 kHz. Isso verifica execução do modelo e formato de saída; os dois trechos são da mesma identidade. Não prova semelhança com outra pessoa ou qualidade natural de um clone.

| Teste | Resultado observado |
|---|---|
| Primeira carga, com download inicial do marcador | 21,71 s |
| Primeira conversão de 6 s, incluindo aquecimento | 23,76 s |
| Nova sessão: primeira conversão de 6 s | 8,31 s |
| Mesma sessão: conversões seguintes de 6 s | 2,96 s e 2,84 s |
| Saída da conversão | WAV PCM16 mono 16 kHz, aproximadamente 5,99 s |
| Pico de memória na primeira sessão | 1.383.888 KiB de RSS |
| Experimento: 1 s, embeddings de origem/destino em cache | 2,00 / 1,50 / 1,03 / 0,85 / 2,10 s |
| Experimento: 1 s, uma thread | Após aquecimento, aproximadamente 1,26–1,46 s |

Os números foram transcritos das saídas das execuções desta sessão. Não são medições no Render, na rede móvel ou em ligação real. O experimento de blocos curtos usou uma rotina de diagnóstico; não é um modo disponível no app. Houve aviso do motor de que o trecho curto não comportava toda a marcação, outro motivo para não habilitá-lo em chamadas.

A memória observada excede os 512 MB do plano gratuito do Render. Um plano maior resolve a capacidade de memória; não garante sozinho latência ou naturalidade. Nenhum plano pago foi contratado.

## Reproduzir com seus arquivos autorizados

Em um ambiente virtual Python 3.12, execute `bash server/setup-openvoice.sh`. Use WAV mono PCM16 a 16 kHz, entre 1 e 60 s para origem e uma referência de fala autorizada limpa de 3 a 30 s. Execute:

```bash
python server/check_openvoice.py \
  --config .models/checkpoints_v2/converter/config.json \
  --checkpoint .models/checkpoints_v2/converter/checkpoint.pth \
  --source origem.wav --reference referencia.wav \
  --output resultado.wav --report desempenho.json
```

O diagnóstico executa três conversões reais, confere formato, duração e saída não silenciosa. Não realiza chamadas, publica áudios ou atesta identidade. O resultado e o relatório ficam nos caminhos indicados. Use somente checkpoints oficiais ou outros arquivos que você confie, pois a versão fixada do carregador usa desserialização PyTorch.
