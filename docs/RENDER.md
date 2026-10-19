# Servidor Jarvis Voice no Render

## Serviço implantado

- URL: https://jarvis-voice-relay.onrender.com
- Workspace: My Workspace; região Virginia; compute plan free.
- Serviço: srv-dbaubtjrjlhs73ano5s0; implantação dep-dbaubu3rjlhs73ano7o0, estado live.
- Deploy por criação direta via Render, com auto-deploy desligado. O Blueprint é uma configuração de referência e não gerencia o serviço atual. A verificação configurada por essa criação é TCP; o endpoint HTTP `/healthz` também foi verificado externamente.
- Teste remoto: autenticação, dois clientes PCM bidirecionais, descarte de versão antiga, confirmação `ready` da versão atual e encerramento passaram. Não é teste em celular, operadora ou provedor telefônico.
- No Android, use a origem HTTPS acima e copie `JARVIS_TOKEN` de Environment no painel privado do Render. A chave não está no código nem no APK.

## O que está preparado

O `render.yaml` e `server/Dockerfile` iniciam a ponte HTTP/WSS em Python 3.12, com a aplicação escutando em `0.0.0.0` e na porta `PORT`. O processo roda sem root. A chave privada `JARVIS_TOKEN` é gerada pelo Render, nunca fica no repositório. O verificador público `/healthz` retorna somente `{"status":"ok"}`; vozes, chamadas e diagnósticos exigem autenticação.

O plano declarado é `free` e `ENABLE_TELEPHONY=false`. Essa configuração é para testar chamadas entre apps com efeitos locais e ambientes. Não instala OpenVoice, não compra número e não liga para nenhum destino.

O plano gratuito tem suspensão por inatividade, limites de uso e armazenamento efêmero. Não é hospedagem de telefonia contínua. Referências cadastradas nesse armazenamento desaparecem após substituição da instância; guarde os originais. Nenhuma referência privada de Carlos Jr. está no pacote ou deve ser adicionada ao Git público.

## Publicar

1. Coloque o conteúdo deste projeto na raiz de um repositório próprio no GitHub. O Render precisa alcançar esse repositório. O repositório próprio foi criado em https://github.com/Carlosjr81/Jarvis-Voice ; Treino Livre e Jarvis Studio Editor são projetos distintos.
2. No workspace `My Workspace` confirmado pelo usuário, crie o Blueprint a partir desse repositório e confira que ele propõe apenas o serviço `jarvis-voice-relay` no plano `free`.
3. Após a implantação chegar a `live`, verifique `/healthz`, depois `/api/health` com a chave privada. Informe origem HTTPS e chave no Android. Não publique a chave.
4. Teste duas instalações em primeiro plano e na mesma sala; escolha voz/ambiente e aceite dos dois lados. Na rota entre apps, o bip confirma envio do áudio atual, não reprodução física no outro aparelho.
5. Para números telefônicos, são necessários os dados do provedor listados em [TELEFONIA.md](TELEFONIA.md). Só então habilite a central e teste um destino autorizado.

O arquivo foi validado pelo esquema JSON público do Render. Foi instalada a CLI Render 2.30.0 e tentada a validação autenticada; ela exigiu login separado da conexão do Render e não foi concluída. O build de imagem e a implantação por criação direta no Render foram concluídos depois dessa validação. A conexão do Render e a escolha do workspace, por si só, não publicam os arquivos.

## Motor neural

O motor para gravações precisa de outro dimensionamento e instalação; o uso medido excede a memória do plano gratuito. Veja [OPENVOICE.md](OPENVOICE.md). A troca neural em conversa não foi habilitada porque o ensaio de blocos curtos não acompanhou a fala de forma estável.

Referências oficiais: https://render.com/docs/blueprint-spec ; https://render.com/docs/free ; https://render.com/docs/websocket ; https://render.com/docs/health-checks .
