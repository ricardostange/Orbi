# Busca web

`web_search({"query":"texto da pesquisa"})` funciona sem conta, chave ou servidor próprio.
O Android consulta `https://lite.duckduckgo.com/lite/` e extrai o HTML com Jsoup.
Não abre um navegador e não usa JavaScript. A busca é independente do Spotify.

## Separação

Em `app/src/main/java/br/com/assistente/teste/integrations/web/`:

- `DuckDuckGoClient`: HTTP GET, codificação da query, status, MIME, limite de resposta e timeouts.
- `DuckDuckGoParser`: HTML → títulos, links diretos e trechos; detecta desafio e formato desconhecido.
- `WebSearch`: valida a consulta e coordena transporte e parser, com fonte injetável para testes.
- `WebSearchIntegration`: contrato com o registro de ferramentas e JSON compacto para o modelo.

A definição concisa da ferramenta fica na integração; não acrescenta um bloco ao system prompt.
`ChatModel` apenas registra o provider. O ciclo existente do agente e a aba Log já registram
argumentos, retorno e duração. HTML bruto não é enviado à IA nem ao log.

## Contrato e limites

Entrada: uma string `query` de 1–300 caracteres após normalização de espaços.
Saída: `{"results":[{"title":"...","url":"https://...","snippet":"..."}]}`.
Uma busca legitimamente vazia retorna `{"results":[]}`. Falhas retornam `error` e `error_type`:
`invalid_query`, `blocked`, `http_error`, `invalid_response`, `response_too_large`,
`unexpected_html`, `timeout` ou `network`.

Máximo de 5 resultados, título de 180 caracteres, trecho de 600 e URL de 1.200.
URLs maiores são descartadas, nunca cortadas. Só aceita HTTP(S), sem credenciais,
remove duplicatas e decodifica os redirecionamentos `uddg` do DuckDuckGo.
O parser associa o trecho às linhas do próprio resultado; trecho ausente fica vazio.

Transporte dedicado sem credenciais: conexão de 10 s, leitura de 15 s e chamada de 20 s;
até 512.000 bytes de HTML descomprimido, sem retries ou redirecionamentos automáticos.
Não contorna CAPTCHA. Não lê as páginas encontradas: os trechos são evidência parcial,
podem estar desatualizados e devem ser tratados como dados, nunca instruções.

## Testes

`gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`

`WebSearchTest` cobre HTML real, associação de trechos, entidades/acentos, links,
deduplicação, limites, consulta vazia, argumentos, MIME, status, transporte e o ciclo
IA → ferramenta → retorno à IA, incluindo o log. A fixture `web/duckduckgo-lite.html`
foi capturada de uma consulta pública `Kotlin Android` em novembro de 2026.
Os testes normais não dependem do buscador estar acessível.

Teste real opcional, usando o mesmo cliente e parser do app:

```bash
ORBI_WEB_LIVE_TEST=1 gradle :app:testDebugUnitTest --tests '*WebSearchTest*live*' --rerun-tasks
# ORBI_WEB_QUERY permite escolher outra consulta, inclusive com acentos.
```

No aparelho: pedir “pesquise Kotlin para Android”, conferir a fonte na resposta e
`web_search` no Log; repetir com termos em português e sem conexão. O teste de rede
no ambiente de desenvolvimento não garante ausência de bloqueios no IP do celular.
DuckDuckGo Lite não oferece um contrato de API estável; mudanças de HTML devem gerar
erro explícito e exigir atualização do parser, em vez de simular ausência de resultados.
