# Orbi

Assistente de conversa Android em Kotlin com Jetpack Compose, ViewModel e coroutines.

[Baixar Orbi.apk](https://github.com/ricardostange/Orbi/releases/latest/download/Orbi.apk)

Configure sua chave DeepSeek no app. Toque em Falar: a transcrição é enviada ao endpoint oficial de chat usando deepseek-flash. A resposta aparece na tela e é lida pelo TextToSpeech do Android. Cada solicitação é independente; conversas não são gravadas em disco. Chamadas consomem o saldo da sua API. Não há ferramentas locais, notas ou comandos fixos.

A chave continua criptografada com o mesmo alias e arquivo privado das versões anteriores. O pacote e a assinatura foram preservados. O reconhecimento usa o idioma do sistema; a voz depende do serviço instalado no celular.

Build: Java 17, Android SDK 35 e Gradle 8.9. Execute `gradle :app:assembleDebug :app:lintDebug`. Releases usam KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_PASSWORD e VERSION_CODE. O GitHub Actions compila e publica automaticamente nas Releases com a assinatura original.
