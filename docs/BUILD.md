# Build e assinatura

O projeto usa Kotlin, Jetpack Compose e Gradle. Requer Java 17, Gradle 8.9 e Android SDK 35. Execute `gradle :app:assembleDebug` para gerar o APK debug e `bash build.sh` para o release assinado ou `bash scripts/test.sh` para testes, compilação e lint. Configure `ANDROID_HOME` com seu SDK (ou `sdk.dir` em `local.properties`, que não deve ser versionado).

A chave original é uma chave de desenvolvimento, alias demo, senha android. Ela não é uma chave de produção. Para continuar atualizando o aplicativo existente, mantenha essa chave fora do Git e copie-a para development.jks antes do build. O .gitignore exclui chaves e binários.

O pipeline GitHub Actions está definido no repositório e requer os secrets descritos abaixo para assinar o APK.

Testes: `scripts/test.sh` executa testes JVM das integrações, build debug e lint via Gradle (Java 17 e Android SDK 35). Consulte [INTEGRATIONS.md](INTEGRATIONS.md) para configuração do Spotify e validação em aparelho.

## GitHub Actions

O workflow `.github/workflows/android-apk.yml` executa os testes em pull requests. Pushes em `main` e execuções manuais também compilam e assinam o APK. O arquivo fica em Actions → Android APK → execução → Artifacts por 30 dias. Baixe e extraia o ZIP para instalar o APK.

Antes da primeira compilação, configure em Settings → Secrets and variables → Actions:

- `ANDROID_KEYSTORE_BASE64`: conteúdo da chave **original** development.jks codificado em Base64.
- `ANDROID_KEYSTORE_PASSWORD`: senha do keystore original.
- `ANDROID_KEY_PASSWORD`: senha da chave original.

Não envie essas informações ao Git. O alias permanece `demo`. O build verifica o SHA-256 do certificado original e recusa outra assinatura. A chave temporária é apagada ao terminar a etapa, inclusive em caso de erro. Ausência de secrets causa falha explícita; não é gerada uma nova chave.

Localmente, exporte também `KEYSTORE_PASSWORD` e `KEY_PASSWORD`; `KEYSTORE_PATH` permite apontar para a chave fora do projeto. `VERSION_CODE` é opcional. No CI, o código de versão é 1000 + número da execução. Builds locais destinados a atualizar uma instalação do CI precisam usar um VERSION_CODE maior que o instalado. Reexecuções da mesma execução mantêm o código.

