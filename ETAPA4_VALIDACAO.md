# Etapa 4 — Gerenciamento de usuários e permissões

## Resultado

Implementados os quatro endpoints restantes. Login, controle de tentativas,
bloqueio central, HTTP Basic, BCrypt e política de roles anteriores foram mantidos.
Todas as alterações e arquivos gerados estão dentro de AV02, na unidade D:.

## Arquivos

Criados:
- `src/main/java/br/edu/microservices/av02/UserController.java`;
- `src/main/java/br/edu/microservices/av02/UserService.java`;
- `src/test/java/br/edu/microservices/av02/UserManagementIntegrationTest.java`;
- `ETAPA4_VALIDACAO.md`.

Modificados:
- `src/test/java/br/edu/microservices/av02/PersistenceRestartTest.java`;
- `README.md`.

Sem alterações no SecurityConfig, LoginController, LoginService, initializer,
schema.sql, application.properties, pom.xml, dev.ps1 ou AV01. Nenhuma instalação,
dependência, endpoint extra, commit ou publicação foi adicionada.

## Contrato final

`userid` é a chave username. As escritas e os erros retornam corpo vazio.

| Método e rota | Corpo | Acesso | Status |
|---|---|---|---|
| POST /login | username, senha | Público | 200, 401, 400 |
| PUT /trocarsenha/{userid} | atual, nova | Público; valida senha atual | 204, 401, 400 |
| POST / | username, senha | ROLE_MANUTENCAO com Basic | 201, 409, 400, 401, 403 |
| PUT /unlock/{userid} | Nenhum | ROLE_ADMIN com Basic | 204, 404, 401, 403 |
| GET / | Nenhum | ROLE_MANUTENCAO com Basic | 200, 401, 403 |

GET / retorna array ordenado de objetos contendo somente username, por exemplo
`[{"username":"ana"}]`; sem resultados, retorna `[]`.
ADMIN não herda MANUTENCAO. ROLE_USER não autoriza cadastro, listagem ou desbloqueio.
Contas bloqueadas não conseguem executar as operações protegidas, mesmo com a senha correta.

## Decisões e consistência

**Authority de novos usuários:** recebem exclusivamente ROLE_USER. O
JdbcUserDetailsManager não carrega como autenticável uma conta sem authorities;
essa role mínima permite login sem conceder privilégios administrativos. O JSON
de cadastro não vincula roles, authorities ou enabled: esses campos extras são
explicitamente ignorados, nunca aplicados. Não foi criado gerenciamento de roles.

**Cadastro:** usa JdbcUserDetailsManager.createUser e encoder existentes dentro
de transação, preservando a consistência entre usuarios e authorities. A chave
primária impede duplicidade concorrente; DuplicateKeyException é traduzida para
409 somente após o rollback do serviço. Não há resposta com credenciais.

**Troca pública:** utiliza comparação BCrypt direta com a senha atual, pois o
AuthenticationManager deve rejeitar contas bloqueadas, enquanto a troca pode
alterar a senha sem desbloquear. O UPDATE modifica somente usuarios.senha;
enabled, authorities e login_attempts não são alterados. Não depende do principal
HTTP Basic nem do changePassword contextual do gerenciador JDBC. Usuário inexistente
e senha atual incorreta recebem o mesmo 401.

**Concorrência:** troca de senha e desbloqueio usam SELECT FOR UPDATE sobre a
mesma linha de usuarios usada pelo LoginService. Assim, duas trocas com a mesma
senha antiga não podem validar simultaneamente o mesmo hash; operações concorrentes
de login e desbloqueio também têm um resultado correspondente à execução serial.

**Desbloqueio:** altera somente a linha do alvo em login_attempts, zerando contador,
locked e datas de falha/bloqueio, e atualizando updated_at. Sem linha de tentativas,
204 sem criar uma linha desnecessária; usuário inexistente retorna 404. Uma conta
já desbloqueada também recebe 204. Hash, roles, enabled e outros usuários permanecem iguais.

**Limites técnicos:** usernames de cadastro têm até 100 caracteres, conforme o
schema. Novas senhas de cadastro/troca têm até 72 bytes UTF-8, limite do BCrypt;
excesso retorna 400 em vez de truncar ou gerar erro interno. Campos obrigatórios
ausentes, nulos, vazios ou somente espaços e JSON malformado retornam 400.

## Testes executados em 26/10/2026

| Execução | Resultado |
|---|---|
| `.\dev.ps1 test` | 44 testes de gerenciamento + 30 testes anteriores aprovados |
| `.\dev.ps1 persistence-write` | 1 teste aprovado, JVM PID 15008 |
| `.\dev.ps1 persistence-read` | 1 teste aprovado, outra JVM PID 21084 |
| Maven `-DskipTests package`, após os testes | BUILD SUCCESS; JAR atualizado |

**76 testes efetivamente executados e aprovados, sem falhas ou erros.**
Na execução normal, os dois métodos de persistência ficam ignorados; cada comando
write/read executa um deles e ignora o outro, como nas etapas anteriores.

Cobertura da Etapa 4:

- troca com senha atual correta/incorreta e usuário inexistente;
- nova senha funcional e senha antiga inválida;
- JSON e campos inválidos na troca e no cadastro;
- troca preserva uma falha anterior e também o estado de conta bloqueada;
- cadastro autorizado, duplicidade, hash BCrypt e login da conta criada;
- ROLE_USER sem privilégios e tentativas de injeção de roles/authorities/estado;
- matriz HTTP de cadastro, desbloqueio e listagem para manutenção, admin,
  usuário comum e ausência de autenticação;
- desbloqueio do alvo, contador/datas zerados, idempotência e 404;
- hash e roles preservados no desbloqueio e outro usuário bloqueado não alterado;
- atores bloqueados impedidos de cadastrar, listar e desbloquear;
- listagem HTTP com somente username e sem senhas/hashes;
- serviço em banco vazio e serialização real resultando em `[]`;
- dois cadastros simultâneos: um 201, um 409, uma conta/authority consistente;
- duas trocas simultâneas com a mesma senha atual: um 204, um 401 e somente uma nova senha válida;
- desbloqueio e falha simultâneos: contador final 0 ou 1, sem bloqueio e com datas coerentes;
- persistência de cadastro, nova senha, ROLE_USER e desbloqueio entre JVMs;
- regressão completa de login, bloqueio na segunda falha, HTTP Basic, concorrência,
  hashes, roles e persistência das etapas anteriores.

Os testes HTTP usam servidores reais em portas aleatórias e bancos H2 isolados.
A listagem vazia foi verificada no serviço e no ObjectMapper real da aplicação:
num banco sem usuários também não existe uma conta JDBC de manutenção para
autenticar uma chamada HTTP. A listagem HTTP autorizada foi verificada com dados.

O teste persistente mantém as fixtures anteriores e adiciona uma conta comum,
cadastrada, com senha trocada, bloqueada por HTTP e desbloqueada pelo serviço.
Após reinício, verifica a nova senha e os dados preservados, sem desbloquear a
outra conta usada para a regressão do bloqueio central.

O banco normal data/av02.mv.db não participa dos testes. Seu SHA-256 antes da
implementação era `138293443A3EFD5F9A85A4508BE5DB3862223E387F53646F9FCD752F76F5CB92`.
Logs locais: `.local/etapa4-tests.log`, `.local/etapa4-persistence-write.log`,
`.local/etapa4-persistence-read.log` e `.local/etapa4-package.log`.

## Regressões, pendências e próxima etapa

Nenhuma regressão funcional encontrada. Os testes anteriores passaram; o teste
de persistência foi ampliado para incluir os dados da Etapa 4. Nenhuma pendência
de implementação ou teste restante além do alcance explicitado para lista vazia.

Não foram implementados métricas, Prometheus, Grafana, Swagger/OpenAPI, JWT,
OAuth2, frontend ou administração de roles.
Próxima etapa: observabilidade, somente após autorização.
