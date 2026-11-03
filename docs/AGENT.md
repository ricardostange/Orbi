# Agente e integração com LLMs

## Onde alterar

| Responsabilidade | Arquivo |
| --- | --- |
| Provedor, endpoint, modelo, geração e timeouts padrão | `app/src/main/assets/agent/provider.json` |
| Papel, tom, segurança e comportamento geral | `app/src/main/assets/agent/prompts/system.md` |
| Orientações e exemplos do Spotify | `app/src/main/assets/agent/prompts/spotify.md` |
| Contexto de ferramentas conectadas / desconectadas | `connected.md` e `disconnected.md`, na mesma pasta |
| Correção de chamada escrita como texto | `repair.md`, na mesma pasta |
| Cliente HTTP compatível com OpenAI chat/completions | `llm/OpenAiCompatibleClient.kt` |
| Histórico e ciclo de chamadas de ferramentas | `llm/ChatClient.kt` |
| Leitura dos arquivos e preferências no Android | `llm/AgentSettings.kt` |
| Esquemas e validações executáveis do Spotify | `integrations/spotify/SpotifyIntegration.kt` |

Os caminhos Kotlin são relativos a `app/src/main/java/br/com/assistente/teste/`.
Os Markdown são carregados pelo aplicativo, não apenas documentação. Edite-os e gere um novo APK; não é necessário alterar Kotlin para mudar instruções. O prompt Spotify só é anexado quando a ferramenta está conectada.

O contrato `LlmGateway` separa transporte e agente. A UI conhece `ChatModel`, não requisições HTTP. Os esquemas JSON, allowlist de ações, validações de IDs/intervalos, autenticação, tratamento de respostas, limites de 6 etapas/8 ações e histórico de até 5 turnos permanecem em código: instruções em linguagem natural não substituem esses controles.

## Configurar no celular

Menu → Configurações → Configurar API e modelo. Cole a chave; as opções avançadas permitem alterar provedor, endpoint HTTPS completo, modelo, temperatura, tokens e timeouts. Configurações são locais e persistem entre atualizações. Os padrões do JSON valem quando ainda não há uma configuração salva.

O provedor precisa oferecer `chat/completions` e chamadas estruturadas `tool_calls`. Mudar o nome exibido não adapta protocolos diferentes. Ao trocar de provedor, use o endpoint/modelo dele e remova parâmetros exclusivos do DeepSeek (`extra_body.thinking`), usando `{}` se necessário. A UI exige uma nova chave ao trocar o endpoint para evitar enviar a chave anterior a outro serviço.

Trocar a configuração reinicia o contexto interno do agente; navegar entre telas não reinicia a conversa. A última pergunta/resposta permanece visível. A conversa fica em memória e sobrevive à rotação via ViewModel, mas não ao encerramento do processo. Não há armazenamento permanente de histórico.

A chave continua na mesma entrada criptografada de `ApiKeyStore`, usando Android Keystore. Tokens Spotify e o Client ID previamente configurados também são preservados. Não coloque credenciais em JSON, Markdown, exemplos ou Git. O aplicativo não registra cabeçalhos de autorização nem corpos HTTP em diagnóstico. Relatórios de diagnóstico incluem pergunta, resposta e informações consultadas: revise antes de compartilhar.

## Teste manual isolado (Python 3, sem dependências)

Inspecionar a requisição exata, sem internet e sem chave:

```bash
python3 scripts/llm_probe.py --message 'Olá'
python3 scripts/llm_probe.py --message 'Pause a música' --spotify-tools
```

Enviar uma requisição real (consome a API):

```bash
python3 scripts/llm_probe.py --message 'Pause a música' --spotify-tools --send
```

A chave é solicitada sem eco. Alternativamente, forneça `ORBI_LLM_API_KEY` pelo ambiente do seu gerenciador de segredos. Não coloque a chave como argumento do comando ou em arquivo versionado.

Usar outro arquivo de provedor / prompt experimental:

```bash
python3 scripts/llm_probe.py --config /tmp/provider.json --prompt /tmp/system.md --message 'Olá' --send
```

`--prompt` substitui `system.md` e aceita múltiplos arquivos em ordem. As instruções de disponibilidade e Spotify continuam anexadas conforme `--spotify-tools`. O schema é extraído diretamente da constante versionada do Spotify, evitando uma cópia divergente.

O teste mostra mensagens, parâmetros, resposta e tool calls propostas. **Não executa ferramentas**, não autoriza Spotify e não cria histórico: é uma única chamada isolada. Para validar o ciclo completo com efeitos reais, use o app conectado. O CLI usa timeout de socket limitado pelo timeout total; os timeouts separados de conexão/leitura e o prazo total são aplicados integralmente no cliente Android. Chaves conhecidas são ocultadas na saída do CLI. Não use dados privados em testes que pretenda compartilhar.

## Diagnóstico

- Conexão/timeout: confira internet, HTTPS, endpoint e limites; não repita uma ação sem verificar seu estado no Spotify.
- HTTP 401/403: chave ou acesso recusado; 402: saldo; 429: limite de requisições.
- Parsing: confira suporte ao envelope `choices[].message` e a `tool_calls`; nenhum comando escrito como texto é executado. O agente tenta uma correção e encerra se o problema persistir.
- Ferramenta: confira conexão da integração, disponibilidade de dispositivo, argumentos e resultado. O agente só oferece ferramentas conectadas e valida as ações na implementação.
- OAuth Spotify: menu → Configurações → Gerenciar integrações → Testar conexão. O retorno do navegador abre essa área. Diagnósticos permanecem acessíveis e copiáveis, fora da tela inicial.

## Verificação

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
scripts/test.sh
```

Os testes JVM usam respostas HTTP simuladas: verificam histórico, execução única, reparo de tool calls, indisponibilidade, configuração e credenciais. Nenhuma chave real é necessária. A CI executa testes, compila Compose e roda lint antes de publicar o APK assinado.

Checklist no aparelho: abrir/fechar o drawer e voltar com gesto/botão; navegar durante uma consulta e conferir a resposta ao retornar; girar a tela; copiar/selecionar texto; conferir chave/conexão após atualizar; conectar Spotify pelo navegador e testar reprodução; revisar layout com fonte ampliada e tela pequena. Não confundir compilação/testes JVM com validação visual ou autorização real em aparelho.

## Abrir Spotify e iniciar música

`android_open_spotify` é uma integração Android independente de OAuth. Usa o launch intent do pacote `com.spotify.music`, declarado em `<queries>` para Android 11+. O launcher só fica anexado enquanto a Activity está iniciada e é executado na thread principal; não se retém Activity no ViewModel após `onStop`. App ausente ou impossibilidade de abrir gera erro, sem afirmar reprodução.

`spotify` com `action=play_query` e `query` resolve busca de faixa → escolha de dispositivo elegível → `play` direcionado → consulta do estado. A regra é primeira faixa reproduzível na ordem de busca, dispositivo ativo não restrito ou primeiro não restrito. `device_id` opcional permite escolher explicitamente; se o escolhido não está disponível, não toca em outro silenciosamente. Um dispositivo inativo pode receber play com `device_id` sem transferir antes. `transfer` continua disponível para seleção explícita de player.

`command_accepted` indica apenas aceitação HTTP; `playback_verified` só é verdadeiro quando a consulta mostra `is_playing=true` e a URI solicitada. Falha da consulta posterior não repete o play. Consultas imediatamente após play podem ainda mostrar o estado anterior: a resposta comunica que falta confirmação. Busca sem faixa e dispositivos ausentes/restritos não enviam play. Sem dispositivo, o assistente oferece abrir o Spotify e consultar novamente, sem garantir que abrir ative Connect.

O diagnóstico registra ação/argumentos, resultados estruturados, status HTTP, erro e duração das chamadas Spotify. Tokens, cabeçalhos e campos de credenciais são redigidos; listas e textos são limitados. O relatório pode conter nomes, IDs de dispositivos e faixas consultadas, por isso deve ser revisado antes de compartilhar. Não há gravação automática desses eventos em disco.

Teste em aparelho com Spotify aberto e fechado, sem música ativa, tocando música, sem app instalado, conta sem Premium, apenas dispositivo restrito e permissão insuficiente. Peça “abre o Spotify” e “toca uma música clássica”. Confira `search`, `devices`, `play` e consulta de estado no diagnóstico. Também teste retomar, pausar e próxima. Escopos de reprodução existentes são mantidos; reautorize somente quando houver indicação de permissão insuficiente/credencial inválida. Sem conta real conectada, os testes automatizados simulam as respostas, não comprovam a disponibilidade do Spotify Connect no aparelho.
