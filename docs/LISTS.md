# Listas locais — Orbi 0.6

O menu segue a ordem Início · Listas · Configurações. Em Listas, use + para criar uma lista, toque no nome para abrir, adicione itens pelo campo ou botão + e marque/desmarque pelos checkboxes. Os botões de editar e lixeira permitem renomear e excluir a lista. Há confirmação antes de excluir uma lista ou item.

## Persistência e estado

`lists/ListsRepository.kt` é a única camada de dados da UI e do agente. Usa SQLiteOpenHelper, banco privado `orbi-lists.db`, transações, parâmetros SQL, IDs UUID e ordenação por posição de inserção. Não exige internet, chave LLM ou Spotify para operações na tela. Nomes de listas são únicos após trim e conversão para minúsculas com Locale.ROOT; itens homônimos são permitidos. Erros não deixam alterações parciais dentro de uma operação. Exclusão de lista remove os itens por chave estrangeira com cascade.

Um StateFlow publica snapshots após o commit. Escritas e publicação usam o mesmo lock, evitando que uma escrita concorrente publique um snapshot antigo. O ViewModel executa o acesso ao banco em Dispatchers.IO e compartilha a mesma instância com o IntegrationRegistry. A seleção e os campos da tela usam rememberSaveable.

## Contrato do agente

`ListsIntegration` oferece oito ferramentas estruturadas: `list_lists`, `create_list`, `rename_list`, `delete_list`, `list_items`, `add_item`, `remove_item` e `set_item_completed`. O prompt versionado fica em `app/src/main/assets/agent/prompts/lists.md`. Argumentos são estritos: campos extras, tipos incorretos, campos ausentes e IDs inexistentes são recusados. Remover ou marcar item também valida o vínculo com a lista.

O agente consulta listas e itens antes de resolver nomes para IDs e deve pedir esclarecimento para homônimos. O backend sempre atua por ID, nunca por texto. A interpretação de linguagem natural depende da LLM; os testes usam gateway simulado e não comprovam a qualidade do provedor real.

`delete_list` retorna `deleted=false` e `requires_confirmation=true`. Apenas o botão de confirmação na interface pode executar a exclusão. `confirmed=true` não é um argumento aceito. A confirmação identifica uma solicitação única, é cancelável, não é persistida e é renovada se nome ou quantidade de itens mudar. Uma solicitação não pode substituir outra exclusão pendente. Ao retornar ao chat, o agente precisa consultar o estado novamente: a resposta de solicitação não comprova exclusão.

Vários itens usam chamadas independentes, com resultado por item. O dispatcher continua após um erro individual e repassa resultados reais ao modelo. O pedido é limitado a 40 chamadas e 12 etapas; se atingir o limite, o usuário é orientado a consultar o estado antes de repetir. Não há transação entre todas as chamadas de uma conversa. Diagnósticos ficam fora da conversa principal e usam a sanitização existente; podem conter textos pessoais das listas, portanto devem ser revisados antes de compartilhar.

## Validação

`ListsTest` cobre persistência após reabertura, ordenação, nomes vazios/repetidos, homônimos, vínculo lista/item, atualizações compartilhadas UI/agente, consulta de concluídos, exclusão com confirmação/cancelamento/cascade, confirmação desatualizada, validação de argumentos, uso local sem OAuth, falha parcial em tool_calls reais do dispatcher com gateway simulado e concorrência de escritores.

Antes de considerar validado no aparelho, verificar:

- Criar, renomear e excluir listas; adicionar, marcar/desmarcar e remover itens.
- Fechar o processo, reabrir o app e reiniciar o aparelho, conferindo os dados.
- Ativar modo avião e repetir as ações pela tela. Chat continua exigindo acesso ao provedor.
- Pelo chat: criar Compras, adicionar arroz e leite, consultar, marcar arroz, remover leite e pedir exclusão da lista. Confirmar/cancelar no diálogo e consultar novamente.
- Criar dois itens de mesmo texto e conferir se a LLM pede esclarecimento antes de alterar.
- Conferir acessibilidade, teclado, voltar, rotação e as funções preexistentes do Spotify.

Sem sincronização, compartilhamento, datas, lembretes ou prioridades nesta versão.
