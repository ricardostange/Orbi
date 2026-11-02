# Orbi

Assistente pessoal e ferramentas para Android. Base inicial: protótipo 0.2, em Java nativo.

## Instalar no celular

[**Baixar Orbi.apk — versão mais recente**](https://github.com/ricardostange/Orbi/releases/latest/download/Orbi.apk)

Abra o APK baixado e confirme a instalação no Android. Não é necessário extrair ZIP ou entrar no GitHub. Se solicitado, permita que o navegador instale aplicativos.

Cada build bem-sucedido da branch `main` publica uma Release com o APK assinado. O link acima sempre aponta para a Release mais recente.

## Implementado

- Ativação como assistente padrão via ACTION_ASSIST / VOICE_ASSIST.
- Entrada por voz pelo serviço do celular e alternativa por texto.
- Resposta por TextToSpeech do Android.
- Anotar, listar e remover notas locais por correspondência exata.
- Consultar horário e responder aos comandos de teste.

A remoção distingue maiúsculas, acentos e pontuação; duplicatas são removidas uma por vez.

## Organização

- `src/br/com/assistente/teste/MainActivity.java`: interface e execução das ações.
- `src/br/com/assistente/teste/CommandRouter.java`: interpretação dos comandos.
- `src/br/com/assistente/teste/NoteMatcher.java`: correspondência exata de notas.
- `res/`: recursos Android.
- `tests/`: testes Java sem dependências Android.
- `build.sh`: compilação e assinatura do APK.
- `docs/ROADMAP.md`: próximas funcionalidades.
- `docs/BUILD.md`: preparação do ambiente e assinatura.

## Compilar

Requer Java 17, Python 3, Android SDK Platform 35, Android Build Tools 35.0.0 e Eclipse ECJ 3.33.0.

```bash
export ANDROID_JAR=/caminho/android-sdk/platforms/android-35/android.jar
export BUILD_TOOLS=/caminho/android-sdk/build-tools/35.0.0
export ECJ_JAR=/caminho/ecj-3.33.0.jar
./scripts/test.sh
./build.sh
```

Resultado: `build/Assistente-Teste.apk`.

O projeto ainda utiliza o pipeline enxuto original; não é um projeto Gradle e usa GitHub Actions para testar, compilar e publicar o APK nas Releases.

## Atualizações e assinatura

O identificador `br.com.assistente.teste` foi preservado para manter compatibilidade com o aplicativo instalado. O nome exibido é Orbi.

A chave `development.jks` não está versionada. Para atualizar o APK já instalado, restaure a chave original na raiz antes de compilar. Ela está no pacote de código 0.2 entregue anteriormente. Configure também `KEYSTORE_PASSWORD` e `KEY_PASSWORD` no ambiente. Sem a chave original, o build é interrompido; outra assinatura é recusada. Não desinstale o app se quiser preservar as notas.

## Estado de validação

A versão 0.2 teve compilação, assinatura e 20 verificações de roteamento/correspondência validadas. Não houve teste em emulador ou aparelho físico neste ambiente.

Não há LLM, voz neural própria, palavra de ativação ou timer implementados nesta versão. A revisão da interface e o timer estão pendentes.
