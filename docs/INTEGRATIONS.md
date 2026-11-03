# Integrações

## Conectar o Spotify

1. Crie um aplicativo no [Spotify for Developers](https://developer.spotify.com/dashboard), com Web API habilitada.
2. Cadastre exatamente o Redirect URI `orbi://callback`.
3. No modo de desenvolvimento, adicione a conta que usará o Orbi aos usuários autorizados no painel. O Spotify exige Premium do proprietário do app nesse modo e limita os usuários autorizados.
4. No Orbi, abra **Integrações**, em **Configuração do Spotify**, confira o **Client ID** pré-preenchido (`c69bf2e430f743d6a84b9b3ff6a52b2e`) ou informe o de outro app e toque em **Conectar Spotify**. Não cole o Client Secret: ele não é usado nem deve ser distribuído em um APK.
5. Autorize no navegador. Ao retornar, o Orbi abre Integrações automaticamente e mostra o resultado real. Use **Testar conexão** para verificar acesso e dispositivos; **Abrir Spotify** ajuda a ativar a reprodução.
   Se o navegador mostrar `INVALID_CLIENT` ou `Invalid redirect URI` antes de retornar, abra **Configuração do Spotify**: copie o Redirect URI e salve-o no painel do app correspondente ao Client ID. Confirme a conta em **Users and Access**.
6. Para controlar por voz, configure também a chave DeepSeek em **Configurar API**. Os botões Retomar, Pausar e Próxima não precisam dessa chave.

Exemplos: “toque [música]”, “pause o Spotify”, “volume em 30%”, “ative o aleatório”, “repita esta música”, “avance para um minuto”, “quais dispositivos estão disponíveis?”, “toque na sala”, “adicione esta música à fila”, “salve esta música”, “crie uma playlist chamada Viagem”, “adicione esta música à playlist Viagem”. Pedidos ambíguos podem exigir esclarecimento.

### Recursos implementados

- Perfil, estado atual, dispositivos, fila e histórico recente.
- Busca de faixas, álbuns, artistas, playlists, episódios e programas.
- Retomar/tocar faixas ou um contexto, pausar, próxima/anterior, posição, volume, aleatório e repetição.
- Transferência de reprodução e adição à fila.
- Listagem de playlists e seus itens; criação de playlist privada e adição/remoção de faixas ou episódios.
- Consulta de músicas salvas; salvar/remover músicas, episódios, álbuns e playlists da biblioteca.
- Paginação por `offset` nas listagens de playlists, itens e músicas salvas.

Isso **não é acesso irrestrito à conta**: o Orbi controla apenas as operações acima, permitidas pela Web API. Não altera assinatura, pagamentos, senha, downloads, equalizador ou arquivos locais. Reprodução requer Premium e dispositivo ativo; abra o Spotify e inicie uma música se nenhum dispositivo aparecer. Volume e outros comandos podem ser bloqueados pelo dispositivo. No modo de desenvolvimento, o Spotify restringe leitura de conteúdo de playlists de terceiros e pode limitar endpoints. Foram usados os endpoints atuais `/me/library`, `/me/playlists` e `/playlists/{id}/items`, não os antigos endpoints removidos nesse modo.

### Segurança e privacidade

- OAuth Authorization Code + PKCE S256 no navegador externo, sem Client Secret.
- `state` aleatório, de uso único, expira em dez minutos; validação do URI de retorno.
- Tokens, Client ID e dados temporários do OAuth são criptografados via Android Keystore, em armazenamento privado sem backup. A chave DeepSeek mantém arquivo/alias anteriores.
- Refresh automático antes de expirar e uma renovação em caso de HTTP 401. Sem repetição automática de comandos por falha de rede ou limite de uso.
- Desconectar apaga os tokens locais, mas não revoga a autorização no servidor. Para revogar, remova o aplicativo nas permissões da conta Spotify.
- Ao usar voz/conversa, a pergunta e os resultados das ferramentas (por exemplo, nomes de músicas, dispositivos e playlists) são enviados ao DeepSeek. Tokens não são enviados. Até cinco pedidos anteriores concluídos, incluindo respostas e chamadas/resultados das ferramentas, são reenviados como contexto (limite total de 60 mil caracteres; pedidos completos mais antigos são descartados). Esse histórico fica apenas na memória, não é persistido e é limpo ao encerrar o processo ou conectar/desconectar uma conta Spotify. Pedidos que falham antes da resposta final não entram no histórico.
- O modelo só pode selecionar operações explicitamente permitidas, não URLs arbitrárias. Parâmetros são validados; há limites de seis etapas/oito ações por pedido. Conteúdo retornado é tratado como dado não confiável no prompt.
- Alterações de biblioteca/playlists só devem ser solicitadas explicitamente. Se ocorrer erro depois de um comando, consulte o estado antes de repetir: o servidor pode já tê-lo aplicado. Não há transação entre múltiplas ações.

## Adicionar outro sistema

- Implemente `Integration` em `app/src/main/java/br/com/assistente/teste/integrations/<sistema>/`.
- Exponha `id`, `connected`, schemas de ferramentas e `execute`. Mantenha autenticação, transporte HTTP e validação no módulo do provedor.
- Registre a instância em `IntegrationRegistry` no ponto de composição (`ChatModel`). Nomes de ferramentas devem ser únicos.
- Adicione a configuração da conexão em `IntegrationsDialog`; não coloque código específico do provedor no `ChatClient`.
- `ChatClient` envia somente schemas de provedores conectados e devolve resultados à conversa usando o protocolo `tool_calls`.
- Execute operações bloqueantes em IO. Nunca devolva credenciais como resultado de ferramenta.

`SpotifyTransport` permite testar o roteamento de operações sem Android, rede ou conta real. `SpotifyClient` cuida de autenticação HTTP, erros e redução de metadados. `SpotifyAuth` cuida exclusivamente do ciclo OAuth e credenciais.

## Validação

Execute com Java 17, Gradle 8.9 e Android SDK 35:

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
```

Os testes JVM cobrem registro/desconexão de provedores, schema, roteamento de reprodução, parâmetros inválidos, IDs/URIs, biblioteca, playlists, transferência e paginação. OAuth/Keystore e respostas reais do Spotify precisam de validação em aparelho; não são cobertos pelos testes JVM.

Checklist em aparelho: autorização aceita/negada; retorno após fechar o processo durante login; rejeição de `state` inválido e callback repetido; retomada após expiração do token; ausência de dispositivo; Premium/permissões; erro de rede e rate limit; desconectar e conectar outra conta; comandos por voz e botões; conferir playlist/biblioteca após alterações.

Referências: [PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [migração Dev Mode](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).

Reconectar ao mesmo Client ID preserva a sessão anterior até a nova autorização ser concluída. Trocar o Client ID limpa os tokens do aplicativo anterior. O retorno de autorização aguarda pedidos em andamento, sem ser descartado por `busy`.

## Mensagens e diagnóstico (0.4)

O Orbi recebe pedidos por voz, mas responde somente em texto. Perguntas, respostas e estado da conexão permitem seleção; **Copiar resposta** copia a resposta inteira. **Copiar diagnóstico** inclui versão/build, pergunta, resposta, estado do Spotify e etapas da última conversa (ferramentas disponíveis, chamadas estruturadas, execução e erros). Não inclui chave de API, tokens, códigos OAuth nem URI de callback com parâmetros.

Ao receber o callback, a tela principal e Integrações mostram o andamento. Após a troca do código por tokens, o app consulta dispositivos automaticamente e mostra sucesso ou a falha de acesso. Token salvo e acesso à API são verificações diferentes.

Chamadas escritas no conteúdo como JSON/DSML não são executadas como texto. O app solicita uma correção de protocolo uma vez e executa apenas `tool_calls` estruturados. Se não houver integração conectada ou a resposta continuar malformada, mostra um erro copiável. Testes simulam o ciclo HTTP completo, execução única, recuperação de chamada textual e bloqueio quando desconectado.

### Retorno do navegador

`SpotifyCallbackActivity` recebe o deep link e encaminha o URI original para `MainActivity`, inclusive quando a tela principal já está aberta. A validação aceita caminho vazio ou `/`, mantendo verificação de origem, `state`, prazo e PKCE. Caminhos inesperados, usuário/porta no URI e fragmentos são rejeitados. Retornos inválidos não são ignorados silenciosamente.

O diagnóstico registra apenas esquema/host/caminho e presença dos parâmetros OAuth, sem valores de código ou state. Ao retomar o app sem callback, a interface informa que o retorno ainda não chegou. Testes Robolectric verificam resolução no manifesto, encaminhamento do intent, barra final e rejeição de origens inválidas. Isso não substitui a validação do login real em aparelho.
