# Orbi

Assistente pessoal e ferramentas para Android. Base inicial: protótipo 0.2, em Java nativo.

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

O projeto ainda utiliza o pipeline enxuto original; não é um projeto Gradle e não tem CI configurado.

## Atualizações e assinatura

O identificador `br.com.assistente.teste` foi preservado para manter compatibilidade com o aplicativo instalado. O nome exibido ainda é Assistente Teste.

A chave `development.jks` não está versionada. Para atualizar o APK já instalado, restaure a chave original na raiz antes de compilar. Ela está no pacote de código 0.2 entregue anteriormente. Sem essa chave, o script gera uma nova chave de desenvolvimento: esse APK não atualizará a instalação anterior. Não desinstale o app se quiser preservar as notas.

## Estado de validação

A versão 0.2 teve compilação, assinatura e 20 verificações de roteamento/correspondência validadas. Não houve teste em emulador ou aparelho físico neste ambiente.

Não há LLM, voz neural própria, palavra de ativação ou timer implementados nesta versão. A revisão da interface e o timer estão pendentes.
