# Agente e integração com LLMs

## Totais no Log (0.9.1)

Cada comando mostra uma linha discreta com tokens e tempo, também no topo das etapas e
no texto copiado. Tokens somam o `usage.total_tokens` de todas as respostas da LLM;
quando ausente, usa `prompt_tokens + completion_tokens` apenas se ambos forem informados.
Inclui respostas truncadas e as chamadas intermediárias de ferramentas. Detalhes de cache
ou raciocínio não são somados de novo. Não estima por caracteres nem por `max_tokens`.
Etapas sem uso informado ou sem resposta deixam o valor parcial/não informado.

O tempo é a duração total do comando, incluindo LLMs e ferramentas, medida por relógio
monotônico até sucesso, erro ou cancelamento. Os totais são persistidos independentemente
do limite dos eventos. Logs antigos mostram “—”; processos encerrados abruptamente não
recebem uma duração inventada. Durante execução, tokens representam o consumo recebido
até aquele momento e o tempo aparece como “em andamento”.

## Streaming e latência

O cliente pede `stream: true` e `stream_options.include_usage: true`. `ChatStreamReader`
interpreta SSE e monta a resposta por `tool_calls[index]`, concatenando os argumentos.
Texto chega à tela em atualizações limitadas a aproximadamente 20 por segundo, com
atualização final garantida. Uma nova etapa limpa o texto provisório anterior.
Ferramentas só são entregues ao agente depois de `[DONE]`, `finish_reason=tool_calls`
e validação de todos os IDs e argumentos JSON do lote. Streams interrompidos,
truncados ou inválidos não executam as ferramentas daquela resposta. Não há retry
HTTP automático. Resultados JSON completos continuam aceitos quando o servidor
ignora streaming; provedores que recusam a opção retornam erro, sem reenvio silencioso.

O diagnóstico de cada chamada registra tempo até o primeiro conteúdo (texto ou
fragmento de ferramenta) e tempo total, usando relógio monotônico. Eventos de papel,
keepalive e usage não contam como primeiro conteúdo. Em respostas JSON completas,
o primeiro conteúdo só é observado após receber o corpo. A latência inclui rede e
espera no provedor: não é uma medida isolada do processamento do prompt.

Tokens vêm exclusivamente de `usage`, inclusive quando a geração termina truncada.
O cliente aceita usage no último chunk de conteúdo ou num chunk separado sem choices.
Cache hit/miss são exibidos quando informados; `prompt_tokens_details.cached_tokens`
também é reconhecido. Esses valores são detalhamentos da entrada, não tokens adicionais.
Ausência de usage é registrada como desconhecida, inclusive em streams interrompidos.
O cabeçalho do Log continua mostrando apenas tokens totais e duração do comando.

O system prompt não muda com o contador de turnos omitidos. Ferramentas conectadas
continuam determinando as instruções de disponibilidade. Na tela do Log, prompts e
schemas ficam recolhidos como `<main prompt>` e `<tool nome>`, expansíveis individualmente.
Essa apresentação não altera o payload enviado à LLM, os dados salvos ou a cópia do log.
Eventos cujo JSON já foi abreviado são exibidos como texto original.

Testes: `ChatStreamTest`, `ChatClientTest`, `LlmUsageTest` e `LlmConfigurationTest`.
Referência de protocolo: https://api-docs.deepseek.com/api/create-chat-completion/

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

O contrato `LlmGateway` separa transporte e agente. A UI conhece `ChatModel`, não requisições HTTP. Os esquemas JSON, allowlist de ações, validações de IDs/intervalos, autenticação, tratamento de respostas, limites de 12 etapas/40 ações e histórico de até 5 turnos permanecem em código: instruções em linguagem natural não substituem esses controles.

## Configurar no celular

Menu → Configurações → Configurar API e modelo. Cole a chave; as opções avançadas permitem alterar provedor, endpoint HTTPS completo, modelo, temperatura, tokens e timeouts. Configurações são locais e persistem entre atualizações. Os padrões do JSON valem quando ainda não há uma configuração salva.

O provedor precisa oferecer `chat/completions` e chamadas estruturadas `tool_calls`. Mudar o nome exibido não adapta protocolos diferentes. Ao trocar de provedor, use o endpoint/modelo dele e remova parâmetros exclusivos do DeepSeek (`extra_body.thinking`), usando `{}` se necessário. A UI exige uma nova chave ao trocar o endpoint para evitar enviar a chave anterior a outro serviço.

Trocar a configuração reinicia o contexto interno do agente; navegar entre telas não reinicia a conversa. A última pergunta/resposta permanece visível. A conversa fica em memória e sobrevive à rotação via ViewModel, mas não ao encerramento do processo. O contexto do agente não é restaurado; a aba Log conserva os últimos 50 comandos para depuração.

A chave continua na mesma entrada criptografada de `ApiKeyStore`, usando Android Keystore. Tokens Spotify e o Client ID previamente configurados também são preservados. Não coloque credenciais em JSON, Markdown, exemplos ou Git. O aplicativo não registra cabeçalhos de autorização nem corpos HTTP em diagnóstico. Relatórios de diagnóstico incluem pergunta, resposta e informações consultadas: revise antes de compartilhar.

## Teste manual isolado (Python 3, sem dependências)

Inspecionar os prompts e schemas sem internet e sem chave. O probe usa resposta JSON
completa (`stream: false`); o app usa SSE. Para testar o protocolo de streaming, use
`ChatStreamTest`:

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

O diagnóstico registra ação/argumentos, resultados estruturados, status HTTP, erro e duração das chamadas Spotify. Tokens, cabeçalhos e campos de credenciais são redigidos; listas e textos são limitados. O relatório pode conter nomes, IDs de dispositivos e faixas consultadas, por isso deve ser revisado antes de compartilhar. A aba Log grava as trocas do agente e os retornos de ferramentas; diagnósticos manuais de conexão continuam nesta sessão.

Teste em aparelho com Spotify aberto e fechado, sem música ativa, tocando música, sem app instalado, conta sem Premium, apenas dispositivo restrito e permissão insuficiente. Peça “abre o Spotify” e “toca uma música clássica”. Confira `search`, `devices`, `play` e consulta de estado no diagnóstico. Também teste retomar, pausar e próxima. Escopos de reprodução existentes são mantidos; reautorize somente quando houver indicação de permissão insuficiente/credencial inválida. Sem conta real conectada, os testes automatizados simulam as respostas, não comprovam a disponibilidade do Spotify Connect no aparelho.

## Listas locais (0.6)

O registro inclui `ListsIntegration` permanentemente, sem depender do Spotify. UI e ferramentas compartilham `ListsRepository` (SQLite e StateFlow). As regras de resolução de IDs, ambiguidade, múltiplos itens e confirmação ficam no prompt `lists.md`. O dispatcher permite até 40 chamadas em 12 etapas para consultar e modificar múltiplos itens, preservando os resultados individuais. A exclusão de lista solicitada pelo agente depende de confirmação na própria interface; argumentos não podem autorizar a remoção. Consulte [LISTS.md](LISTS.md) para contrato, persistência, testes e validação manual pendente.

## Log de comandos (0.7)

Menu → Log mantém os últimos **50 pedidos ao assistente**, do mais recente ao mais antigo, no armazenamento privado do aparelho (`command-log.json`, escrita atômica, sem backup Android). O histórico é separado dos cinco turnos de contexto da IA: limpar o log não altera a conversa nem executa ferramentas. Cada registro contém pedido, provedor/modelo, estado, envios à IA (mensagens e schemas), respostas estruturadas, chamadas/argumentos, retornos de ferramentas, duração e erros. Envios seguintes incluem o contexto efetivamente enviado ao modelo. Logs anteriores à atualização não são reconstruídos. Controles manuais de integrações continuam no diagnóstico de Configurações.

Os registros atualizam enquanto o pedido está em andamento. Ao reabrir o app, pedidos sem conclusão ficam como interrompidos; isso não prova que uma ação externa foi desfeita. Todos os dados são sanitizados antes da gravação e da cópia: chave conhecida, Bearer e campos de credenciais, inclusive JSON dentro de argumentos/conteúdo. Não são gravados cabeçalhos HTTP. O conteúdo de mensagens e resultados continua sendo pessoal: revise antes de compartilhar.

Cada evento tem até cerca de 16 mil caracteres; cada comando tem orçamento de 120 mil caracteres/200 eventos, com indicação explícita de abreviação/omissão. Resposta final e erro preservam até 4 mil caracteres adicionais se o orçamento foi atingido. Falha de armazenamento é exibida na aba e não impede a execução do comando. Limpar log exige confirmação na interface e fica indisponível durante pedidos.

Testes: `CommandLogsTest` cobre retenção/ordem, reabertura, interrupção, limpeza, sanitização e tamanho; `ChatClientTest` cobre a ordem das trocas e correlação das ferramentas sem duplicar ações. Validação em aparelho: navegar para Log durante pedido; abrir etapas e copiar; fechar/reabrir; executar 51 pedidos; conferir rotação e fonte ampliada; limpar e conferir que listas/conexões permanecem intactas.

## Eficiência e correção do ciclo genérico

- Histórico: até cinco turnos completos e 50.000 caracteres serializados. Evicção do mais antigo, sem separar chamadas de seus resultados nem truncar IDs/JSON. O prompt fixo avisa que o histórico pode omitir turnos; o contador exato fica apenas no diagnóstico; referências ausentes exigem esclarecimento. O turno atual e resultados atuais permanecem integrais.
- Após cinco minutos de inatividade desde o término do último pedido (inclusive erro/cancelamento), o próximo pedido reinicia o contexto. O relógio monotônico inclui suspensão do aparelho; o Log não é apagado. Interações contínuas renovam o prazo, sem expirar mensagens individualmente.
- A seleção é uma janela por recência e orçamento, não um classificador semântico. Não há chamada extra de resumo nem filtro de ferramentas por palavras-chave que possa esconder capacidades relevantes.
- Validação estrutural do lote antes de efeitos: IDs não vazios/únicos no pedido e envelopes de função válidos. Argumentos são validados pelo schema anunciado (tipo, enum, obrigatórios, campos adicionais, limites numéricos e arrays), além das validações de domínio.
- Falhas do modelo após um lote de ferramentas preservam chamadas e resultados no histórico com indicação de interrupção. Não há repetição automática de ações.
- Respostas com finish_reason=length/content_filter são rejeitadas antes de despachar suas ferramentas. Erros de transporte informam resultado desconhecido; erros internos incluem a classe, sem expor stack trace.
- Diagnóstico informa caracteres de histórico/mensagens/schemas e tokens de entrada/saída quando o provedor retorna usage. Caracteres não são estimativa de tokens.
- O estado Processado indica término do processamento, não sucesso de uma ação externa. A resposta deve distinguir envio, observação e confirmação, sem inventar causas de erro.
- Execução de ferramentas continua sequencial: não se presume que chamadas sejam independentes ou livres de efeitos. Não há lógica nova específica de reprodução nem alterações nas integrações Spotify.

Argumentos e retornos completos aparecem uma vez por execução nos eventos de ferramenta; diagnósticos de progresso não duplicam esses payloads. Erros de schema usam outcome=not_executed, enquanto falhas após despacho permanecem inconclusivas.
