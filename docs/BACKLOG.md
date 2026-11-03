# Backlog Orbi

## A fazer

### Reorganizar a interface com menu lateral de navegação

**Problema:** A interface atual do Orbi concentra conversa e configurações no mesmo espaço, deixando o aplicativo visualmente carregado e complexo de usar.

**Objetivo:** Separar as funcionalidades em telas distintas, com navegação simples e expansível.

**Requisitos:**
- Adicionar um botão discreto de menu (por exemplo, ícone de três linhas) que abra e feche um painel lateral à esquerda, preferencialmente do tipo *navigation drawer*.
- O painel deve listar as seções do aplicativo e permitir alternar entre elas.
- Inicialmente, criar duas seções:
  1. **Início / Conversa:** manter o robô/assistente, a conversa e as funcionalidades já existentes de interação.
  2. **Configurações:** concentrar toda a configuração atualmente exibida na tela principal, incluindo conexões com serviços, chaves de API, autenticação e demais parâmetros do aplicativo.
- Remover os controles de configuração da tela inicial, sem perder sua funcionalidade ou os dados previamente salvos.
- Deixar a estrutura de navegação preparada para acrescentar novas seções no futuro.
- Manter a experiência simples e limpa em celulares, usando Kotlin e Jetpack Compose.

**Critérios de aceite:**
- O app inicia na tela de conversa, sem controles de configuração misturados.
- É possível abrir o menu lateral, selecionar Configurações e voltar para Início.
- Todas as configurações e conexões existentes continuam acessíveis e funcionais na nova tela.
- Fechar o painel ou mudar de seção não apaga indevidamente o histórico da conversa nem as configurações salvas.

**Status:** Implementado em código; aguardando validação automatizada e teste no aparelho.

### Separar e documentar a integração com LLMs e o agente

**Problema:** Os detalhes de conexão com os provedores de LLM, os prompts e as instruções que definem o comportamento do agente não devem ficar espalhados e misturados ao código da interface e das ferramentas. Isso dificulta entender, ajustar e testar cada parte isoladamente.

**Objetivo:** Criar uma separação clara entre a lógica de aplicação, a integração técnica com LLMs e as especificações do agente descritas em linguagem natural, de forma que seja fácil configurar, inspecionar e testar tudo manualmente.

**Requisitos:**
- Organizar os componentes relacionados a LLMs/agente em um módulo ou pasta própria, com responsabilidades bem definidas (cliente HTTP, autenticação, escolha do provedor/modelo, montagem das mensagens, tratamento de respostas e chamadas de ferramentas).
- Externalizar, sempre que possível, os **prompts e as instruções comportamentais do agente** para arquivos de texto ou Markdown versionáveis, legíveis e editáveis sem alterar código Kotlin: papel do assistente, regras, tom, uso de ferramentas, instruções por ferramenta, formato esperado de respostas e exemplos de interação.
- Separar **especificações declarativas** (o que o agente deve fazer, em palavras) de **implementação** (como executar as chamadas em código). Manter documentado o que necessariamente precisa continuar em código, como esquemas de ferramentas e validações.
- Centralizar a configuração do provedor de LLM, endpoint, modelo, parâmetros de geração e timeouts em um local bem definido; permitir trocar provedor/modelo sem reescrever a interface ou as ferramentas.
- Manter chaves e tokens fora dos arquivos versionados, especialmente do GitHub, com armazenamento seguro no aparelho; nunca registrar segredos nos logs ou testes.
- Criar uma maneira simples de **testar manualmente a integração isolada**: enviar uma mensagem de teste, escolher/inspecionar os prompts utilizados e verificar a requisição, a resposta e eventuais tool calls, sem depender de todo o fluxo visual do aplicativo.
- Documentar em Markdown como configurar a integração, onde editar cada prompt, como executar os testes manuais e como diagnosticar falhas (conexão, autenticação, parsing e execução de ferramentas).
- Preservar o funcionamento atual da conversa e das integrações existentes durante a refatoração.

**Critérios de aceite:**
- É possível localizar e editar as instruções do agente em arquivos legíveis, sem procurar strings espalhadas pelo projeto.
- A comunicação com LLMs está isolada da UI, com uma interface clara para o restante do app.
- Existe um procedimento reproduzível para testar mensagens, prompts e respostas/tool calls manualmente.
- Nenhuma chave de API ou token precisa ser colocado nos arquivos de prompts, no repositório ou em logs.
- As funcionalidades atuais continuam operando após a reorganização.

**Status:** Implementado em código; aguardando validação automatizada e teste no aparelho.

### Simplificar e polir visualmente a interface (reduzir clutter)

**Problema:** A interface apresenta elementos demais e compete pela atenção do usuário, prejudicando a clareza e a estética.

**Objetivo:** Criar uma experiência limpa, bonita e minimalista, exibindo apenas o necessário em cada tela.

**Requisitos:**
- Revisar a tela inicial e remover textos, botões, indicadores e elementos decorativos redundantes.
- Priorizar a conversa com o assistente e o controle principal de interação; deixar ações secundárias discretas ou acessíveis pelo menu lateral.
- Manter conexões, chaves e configurações fora da tela inicial, na aba de Configurações já prevista no backlog.
- Aplicar hierarquia visual consistente, espaçamentos adequados, tipografia legível e ícones simples, seguindo boas práticas do Material Design 3 / Jetpack Compose.
- Evitar excesso de cores, bordas, cartões, mensagens técnicas e informações de depuração na interface principal.
- Preservar acesso às funções existentes sem sacrificar usabilidade e acessibilidade.

**Critérios de aceite:**
- A tela inicial mostra somente os elementos essenciais para conversar/interagir com o Orbi.
- As opções avançadas continuam disponíveis, porém não ficam visualmente expostas o tempo todo.
- O visual é consistente, agradável e fácil de navegar em telas pequenas.

**Status:** Implementado em código; aguardando validação automatizada e teste no aparelho.

### Diagnosticar e corrigir abertura e reprodução do Spotify pelo agente

**Contexto observado (Orbi 0.4 / build 1012):** Spotify autorizado; app exibe conexão bem-sucedida e dispositivo `2602BPC18G`. Ao pedir "Toca uma música clássica no Spotify", o DeepSeek executou `spotify.devices`, recebeu resultado e encerrou respondendo que não havia dispositivo ativo. Não houve `spotify.search` nem `spotify.play`. Em outros cenários, o usuário consegue passar faixas, mas o comando para abrir o app Spotify não o abre e iniciar uma música do zero falha.

**Problema a investigar:** Existe uma diferença entre (a) autenticação OAuth, (b) disponibilidade/atividade de dispositivo no Spotify Connect, (c) controle remoto de playback pela Web API e (d) abertura do aplicativo via Android. Não presumir que o problema seja a conexão ou apenas o prompt: verificar as respostas reais da API e a implementação das ferramentas.

**Objetivo:** Permitir que o usuário diga "abre o Spotify" e "toca [música/artista/gênero/playlist] no Spotify" e que o Orbi execute as ações com confirmação baseada no resultado real, tratando indisponibilidade e restrições de modo claro.

**Plano de implementação e investigação:**
1. **Instrumentar o fluxo completo** (preferencialmente em modo de diagnóstico, não na tela de conversa): registrar identificação da ferramenta/ação, argumentos sem segredos, resposta estruturada redigida de dados sensíveis, status HTTP, corpo de erro, tempo e decisão subsequente do LLM. Conferir especificamente a resposta de `spotify.devices`: `id`, `name`, `type`, `is_active`, `is_restricted` e possíveis listas vazias. Nunca logar access/refresh tokens ou chaves de API.
2. **Revisar o contrato das ferramentas oferecidas ao DeepSeek:** verificar se ele dispõe de ações explícitas para listar dispositivos, pesquisar faixa/álbum/playlist, reproduzir, transferir playback e abrir aplicativo Android. Garantir schemas, argumentos e resultados inteligíveis; validar o ciclo de tool calls e retornos antes de nova chamada ao modelo. A ação `devices` sozinha não satisfaz um pedido de reprodução.
3. **Criar ferramenta Android independente** `android.open_app` (ou `android.open_spotify`) para abrir `com.spotify.music` com `PackageManager.getLaunchIntentForPackage`/`startActivity` ou intent adequado; considerar package visibility no Android 11+, contexto de Activity e restrições de inicialização em segundo plano. Detectar app ausente e tratar falhas. Abrir o app é diferente de controlar o player via Spotify Web API.
4. **Corrigir a escolha/ativação do dispositivo:** diferenciar dispositivo retornado mas `is_active=false` de ausência de dispositivos; não desistir automaticamente por inatividade. Se houver dispositivo elegível, direcionar playback por `device_id` e/ou utilizar transferência de reprodução quando necessário. Conferir `is_restricted` e possíveis erros de dispositivo não disponível. Se nenhum dispositivo estiver disponível, oferecer abertura do Spotify local e nova consulta, sem prometer que isso ativará o Connect.
5. **Implementar execução ponta a ponta:** interpretar o pedido, pesquisar conteúdo relevante (por exemplo, música clássica) e obter URI Spotify válida, escolher dispositivo e chamar reprodução; inspecionar resposta HTTP e, quando possível, confirmar estado de playback. Para pedido genérico, definir regra previsível de seleção de faixa/playlist; não responder como se tivesse tocado antes de confirmar sucesso.
6. **Verificar autorização e restrições:** conferir escopos OAuth (`user-read-playback-state`, `user-modify-playback-state`, e demais necessários), expiração/refresh do token, status Premium exigido para determinadas operações de playback, limitações de dispositivos/Spotify Connect e códigos 401/403/404/429. Reautorizar apenas se escopos efetivamente insuficientes.
7. **Evitar que o agente encerre cedo:** ajustar prompts e/ou orquestração para que `spotify.devices` seja etapa intermediária, e não resultado final de `spotify.play`; para fluxo previsível, preferir lógica determinística no app com mensagens de erro estruturadas, em vez de depender exclusivamente do LLM para encadear cada chamada.
8. **Separar UX e diagnósticos:** mensagens simples para o usuário; detalhes técnicos consultáveis e copiáveis em uma tela de diagnóstico/configuração, sem poluir a interface principal.

**Cenários de teste / critérios de aceite:**
- "Abra o Spotify" efetivamente abre o app instalado no Android; na ausência do app há aviso útil.
- "Toca uma música clássica" encontra conteúdo, escolhe dispositivo elegível e tenta iniciar a reprodução; o log demonstra a chamada de `play`, não apenas `devices`.
- Com dispositivo listado e `is_active=false`, o agente tenta o fluxo apropriado em vez de afirmar automaticamente que não há dispositivo.
- Sem dispositivo disponível, com dispositivo restrito, com conta sem Premium ou com token sem escopo, o Orbi informa a causa identificada e a próxima ação viável, sem inventar sucesso.
- Passar/pausar/retomar faixas continua funcionando (sem regressão).
- Testes manuais documentados para app aberto/fechado, reprodução ativa/inativa e falhas HTTP; sem segredos nos logs.

**Status:** Implementado em código; aguardando validação automatizada e teste no aparelho.
