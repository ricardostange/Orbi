# Build e assinatura

O script build.sh executa ECJ (Java para classes), d8 (classes para DEX), aapt2 (manifesto e recursos), empacotamento com Python, zipalign e apksigner.

Dependências oficiais:
- Android SDK: https://developer.android.com/tools
- Eclipse ECJ 3.33.0: https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.33.0/ecj-3.33.0.jar

Para executar: configure ANDROID_JAR, BUILD_TOOLS e ECJ_JAR como descrito no README.

A chave original é uma chave de desenvolvimento, alias demo, senha android. Ela não é uma chave de produção. Para continuar atualizando o aplicativo existente, mantenha essa chave fora do Git e copie-a para development.jks antes do build. O .gitignore exclui chaves e binários.

Para um futuro pipeline GitHub Actions, armazenar a chave e as senhas em Actions Secrets; nunca colocar esses valores no workflow ou no histórico Git. A configuração de CI ainda não foi realizada.

Testes: scripts/test.sh compila apenas CommandRouter, NoteMatcher e RouterTest, sem depender do Android.
