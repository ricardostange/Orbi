# Leitura de páginas

`read_page({"url":"https://exemplo.com/artigo"})` complementa `web_search`:
o agente pode pesquisar, escolher uma URL e ler seu conteúdo principal, ou abrir um
link fornecido diretamente. Não exige chave nova nem altera o system prompt.

O parâmetro opcional `offset` serve apenas para continuar páginas longas: use a mesma
URL e o `next_offset` retornado. Cada resposta contém até 6.000 caracteres de texto,
`title`, URL final, `offset`, `total_chars`, `has_more` e, quando aplicável, `next_offset`.
`extraction` informa `readability` ou `fallback`; `content_limited` indica que o limite
de 100.000 caracteres de conteúdo foi atingido. Não significa leitura integral da página.

## Implementação

Em `integrations/web/page/`:

- `PageClient`: GET HTTPS, redirecionamentos, validação de URL/DNS, MIME, tamanho e timeout.
- `PageExtractor`: Jsoup interpreta o HTML e o charset; Readability4J seleciona o artigo;
  o texto mantém separação entre parágrafos e células de tabelas. Quando a seleção é
  vazia ou menor que 200 caracteres, usa `main`, `article` ou o corpo após limpeza.
- `PageReader`: coordena a leitura e mantém até três páginas em memória por cinco minutos.
  A continuação usa o mesmo conteúdo; se expirar ou for removido do cache, retorna erro
  e pede reinício com offset 0. Não salva páginas no disco.
- `PageReadIntegration`: schema conciso e JSON para o registro de ferramentas existente.

Readability4J 1.0.8 é utilizado com o Jsoup 1.24.1 já presente no aplicativo.
O teste de artigo exige explicitamente extração `readability`, para não mascarar uma
incompatibilidade da biblioteca com um fallback aparentemente bem-sucedido.

## Limites

Somente páginas HTML públicas; até 2 MB de corpo descomprimido, 50.000 elementos,
quatro redirecionamentos e 25 segundos de rede. Links HTTP antigos são atualizados
para HTTPS sem liberar tráfego não criptografado no Android. Sites exclusivamente HTTP
não são suportados. Credenciais em URLs, portas não usuais e endereços locais/privados
são rejeitados, inclusive nas resoluções DNS e redirecionamentos.

Não usa sessão do navegador, não executa JavaScript, não contorna login ou CAPTCHA e
não interpreta PDF. Erros retornam `error` e `error_type`, sem enviar HTML bruto à IA.
O conteúdo extraído é dado externo, não instrução. A aba Log recebe as chamadas e
retornos pelo dispatcher existente. Sites podem bloquear requisições ou entregar uma
página incompleta; a extração é heurística e não garante preservar todas as informações.

## Verificação

```bash
gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
ORBI_PAGE_LIVE_TEST=1 gradle :app:testDebugUnitTest --tests '*PageReadTest*live*' --rerun-tasks
```

`ORBI_PAGE_URL` permite trocar a página do teste de rede. Os testes normais usam fixtures
HTML públicas do jsoup.org (artigo de release e documentação de seletores), além de
cenários sintéticos de charset, tabelas, limites, cache, bloqueios, HTTP e endereços.
Também verificam o encadeamento `web_search` → `read_page` → resposta da IA e Log.

No aparelho: fornecer um link e pedir um resumo; pedir uma busca que exija ler o resultado;
conferir `read_page` no Log; pedir continuação de uma página longa. A validação JVM/CI
não substitui teste em aparelho físico e rede móvel.
