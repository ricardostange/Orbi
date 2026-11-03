# Orbi

Assistente de conversa Android em Kotlin com Jetpack Compose, ViewModel e coroutines.

[Baixar Orbi.apk](https://github.com/ricardostange/Orbi/releases/latest/download/Orbi.apk)

Configure sua chave DeepSeek no app. Toque em Falar: a transcrição é enviada ao endpoint oficial de chat usando deepseek-flash. A resposta aparece somente em texto, selecionável e com botão para copiar. O histórico recente é mantido apenas em memória; conversas não são gravadas em disco. Chamadas consomem o saldo da sua API. Integrações conectadas podem ser acionadas por voz usando ferramentas; listas locais também podem ser editadas pela tela.

## Spotify

Abra **Menu → Configurações → Gerenciar integrações** para conectar sua conta com o Client ID de um app Spotify e OAuth PKCE (sem Client Secret). O Client ID padrão já vem preenchido. Cadastre o Redirect URI `orbi://callback` no painel Spotify. Há controles de reprodução, busca, fila, dispositivos, biblioteca e playlists. Reprodução exige Premium e dispositivo ativo; valem as restrições da API oficial.

[Configuração, recursos, privacidade e como adicionar integrações](docs/INTEGRATIONS.md). Dados consultados via conversa são enviados ao provedor configurado; tokens ficam criptografados no aparelho. Os botões de reprodução não dependem da chave DeepSeek.

A chave continua criptografada com o mesmo alias e arquivo privado das versões anteriores. O pacote e a assinatura foram preservados. O reconhecimento de fala usa o idioma do sistema. O Orbi não sintetiza voz. Use **Copiar diagnóstico** para compartilhar versão, estado da conexão e etapas das ferramentas.

Build: Java 17, Android SDK 35 e Gradle 8.9. Execute `gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Releases usam KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_PASSWORD e VERSION_CODE. O GitHub Actions compila e publica automaticamente nas Releases com a assinatura original.


## Interface e agente

O menu lateral reúne **Início** (conversa e botão Falar), **Listas** e **Configurações** (API, modelo, integrações e diagnóstico). Respostas continuam selecionáveis e copiáveis. O Orbi responde por texto.

Prompts editáveis, parâmetros de conexão e testes manuais isolados estão documentados em [docs/AGENT.md](docs/AGENT.md). Alterações do backlog de novembro estão registradas em [docs/BACKLOG.md](docs/BACKLOG.md).

“Abra o Spotify” usa uma ferramenta Android independente da conexão OAuth. Para tocar música por nome ou gênero, `play_query` busca uma faixa, escolhe dispositivo elegível (inclusive inativo), envia play e verifica o estado antes de confirmar.

## Listas (0.6)

Abra **Menu → Listas** para criar suas listas e adicionar, marcar ou remover itens. Dados ficam no aparelho e a tela funciona sem internet. O chatbot também pode operar suas listas (com internet e API configurada): “Crie uma lista Compras”, “Adicione arroz e leite a Compras”, “Marque arroz como comprado”. Exclusões de listas sempre pedem confirmação na tela. Veja [docs/LISTS.md](docs/LISTS.md).
