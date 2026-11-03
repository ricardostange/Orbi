# Backlog Orbi

## Listas / To-do

Implementação da versão 0.6: seção Listas entre Início e Configurações; tópicos e itens locais com CRUD, checkboxes, confirmação de exclusão, estados vazios, SQLite e atualização compartilhada entre UI e agente. Oito ferramentas estruturadas e prompt modular permitem operar listas pelo chatbot, com consultas por IDs, regras de ambiguidade e resultados individuais para pedidos com vários itens.

Validação automática: testes de persistência, duplicidade, homônimos, integridade, confirmação/cancelamento, falhas parciais, concorrência e dispatcher. Build, testes JVM e lint executados pelo workflow Android APK. O resultado de cada execução é a fonte de verdade; ver GitHub Actions.

Pendente: validar os fluxos de interface e a interpretação de comandos pelo provedor real no aparelho, incluindo modo avião para a UI, reinício do aparelho, acessibilidade e regressão Spotify. Roteiro em [LISTS.md](LISTS.md).

## Histórico

A versão 0.5 implementou navegação lateral, limpeza visual, modularização do agente e correções do fluxo Spotify no PR #1. A validação completa do Spotify em aparelho permanece pendente.
