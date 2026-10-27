# Etapa 2 — POST /login

## Implementação

Implementado somente `POST /login`, público conforme a regra da Etapa 1.
O controller recebe `username` e `senha`, aplica Bean Validation e chama o
AuthenticationManager exposto a partir da configuração existente do Spring Security.
O gerenciador reutiliza JdbcUserDetailsManager, a tabela usuarios e o PasswordEncoder
com BCrypt. As consultas JDBC e a cadeia de autorização anteriores foram preservadas.

Todas as respostas têm corpo vazio. Usuário inexistente e senha incorreta recebem
o mesmo 401. JSON malformado e campos obrigatórios inválidos recebem 400 por um
tratamento local de exceções, sem registrar os dados recebidos.
O sucesso retorna 200, sem salvar autenticação no contexto ou criar sessão/token.

## Arquivos

Criados:
- `src/main/java/br/edu/microservices/av02/LoginController.java`;
- `src/test/java/br/edu/microservices/av02/LoginIntegrationTest.java`;
- `ETAPA2_VALIDACAO.md`.

Modificados:
- `src/main/java/br/edu/microservices/av02/SecurityConfig.java`: somente o bean AuthenticationManager e imports;
- `README.md`: estado atual, contrato e funcionamento do login.

Nenhuma alteração no pom.xml, banco normal, schema, initializer, script ou testes da Etapa 1.
Builds, logs e banco de persistência dos testes continuam nas pastas locais ignoradas em D:.

## Testes executados em 26/10/2026

| Comando | Resultado |
|---|---|
| `.\dev.ps1 test` | 16 testes de login + 3 testes da Etapa 1 aprovados; os 2 métodos de persistência aguardam a seleção de fase |
| `.\dev.ps1 persistence-write` | 1 teste aprovado; gravação da fixture na primeira JVM |
| `.\dev.ps1 persistence-read` | 1 teste aprovado; dados preservados na segunda JVM |
| Maven `-DskipTests package`, após a suíte | BUILD SUCCESS; JAR atualizado sem repetir os testes já aprovados |

Total efetivamente executado: **21 testes aprovados, nenhuma falha ou erro**.
Logs em `.local/etapa2-tests.log`, `.local/etapa2-persistence-write.log`,
`.local/etapa2-persistence-read.log` e `.local/etapa2-package.log`.

| Cenário | Evidência |
|---|---|
| Usuários existentes e senhas corretas | admin e manutencao recebem 200 com credenciais BCrypt |
| Senha incorreta | 401 com corpo vazio |
| Usuário inexistente | Mesmo 401 e corpo vazio |
| JSON malformado, vazio ou null | 400 |
| Username ausente, vazio, somente espaços ou null | 400 |
| Senha ausente, vazia, somente espaços ou null | 400 |
| Tipo JSON inválido no username | 400 |
| Login sem Authorization | 200 com credenciais corretas |
| Rota protegida sem autenticação | 401 |
| Rota protegida com admin sem MANUTENCAO | 403 via HTTP Basic |
| Sessão após login | Nenhum Set-Cookie; cookie store vazio; chamada seguinte sem Basic continua 401 |
| Senhas e hashes em respostas/logs | Respostas vazias e asserções sobre saída capturada aprovadas |
| Falhas repetidas | Não inserem login_attempts, não desabilitam usuário e não impedem login correto posterior |
| Regressão da estrutura e persistência | Todos os testes existentes aprovados |

Os usuários de login são criados pelo TestUsersInitializer existente em um banco H2
exclusivo em memória, com senhas aleatórias. O teste entre reinícios usa o banco
isolado `.local/persistence-check/`, sem alterar `data/av02.mv.db`.
O SHA-256 do banco normal permaneceu igual antes e depois da regressão de persistência
e do empacotamento. A validação HTTP usa servidores reais em portas aleatórias.

## Limites e próxima etapa

Nenhuma pendência de validação nesta etapa. Os testes de 401/403 das rotas futuras
validam a cadeia de segurança; essas rotas continuam sem controllers de negócio.
Não foram implementados tentativas, bloqueio, desbloqueio, troca de senha, cadastro,
listagem, métricas, Prometheus, Grafana ou OpenAPI. Sem novas dependências ou instalações.
Próxima etapa: controle de tentativas e bloqueio, somente após autorização.
