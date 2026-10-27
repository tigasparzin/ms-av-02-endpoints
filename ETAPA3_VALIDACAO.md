# Etapa 3 — Tentativas e bloqueio

## Resultado

Implementado exclusivamente o controle de falhas consecutivas e bloqueio na
segunda senha incorreta em POST /login. Contrato preservado: 200 em sucesso,
401 sem distinção pública entre usuário inexistente, senha incorreta ou conta
bloqueada, e 400 para JSON/campos inválidos. Corpos vazios, sem sessão ou token.

## Arquivos criados

- `src/main/java/br/edu/microservices/av02/LoginService.java`;
- `src/test/java/br/edu/microservices/av02/LoginBlockingIntegrationTest.java`;
- `ETAPA3_VALIDACAO.md`.

## Arquivos modificados

- `LoginController.java`: delegação ao serviço transacional;
- `SecurityConfig.java`: único DaoAuthenticationProvider com verificação central do bloqueio;
- `LoginIntegrationTest.java`: isolamento das tentativas e atualização da expectativa da Etapa 2;
- `PersistenceRestartTest.java`: bloqueio real por HTTP e validação após reinício;
- `dev.ps1`: senha aleatória do teste de reinício compartilhada somente no ambiente do terminal;
- `README.md`: funcionamento e comandos atualizados.

Todos os arquivos Java ficam sob `src/main/java/br/edu/microservices/av02/`
ou `src/test/java/br/edu/microservices/av02/`, conforme a finalidade.
Sem alterações em schema.sql, propriedades, pom.xml, initializer, enunciado ou AV01.

## Armazenamento e atomicidade

`usuarios` continua sendo a única fonte de credenciais. O serviço usa
JdbcTemplate e uma transação para bloquear a linha do usuário com SELECT FOR
UPDATE antes de criar a linha de login_attempts, autenticar e atualizar o resultado.
Isso serializa requisições do mesmo usuário, inclusive duas primeiras falhas
simultâneas, sem depender de locks em memória. O contador é incrementado no SQL.

Primeira falha: failed_attempts=1, locked=false, last_failed_at e updated_at
preenchidos. Segunda falha: failed_attempts=2, locked=true e locked_at preenchido.
Um sucesso anterior ao limite zera as falhas e limpa last_failed_at/locked_at.
Tentativas em conta já bloqueada não alteram contador nem timestamps.
Um sucesso inicial também pode criar uma linha com contador zero.
Usuários inexistentes não recebem registros ou contas fictícias.

O serviço captura BadCredentialsException dentro da transação, registra a falha
e retorna false normalmente. O commit ocorre antes de o controller responder 401;
a exceção de autenticação não provoca rollback das tentativas. Falhas na gravação
das tentativas propagam como erro e provocam rollback.

## Autenticação central e HTTP Basic

O único DaoAuthenticationProvider recebe JdbcUserDetailsManager e PasswordEncoder
existentes. Preserva as verificações de estado da conta e consulta locked no banco
antes de conferir a senha. Tanto AuthenticationManager quanto HTTP Basic usam
esse provider. Não há duplicação de credenciais nem uso de enabled como cópia do
estado de bloqueio. HTTP Basic não incrementa e não reinicia o contador de /login.
Roles, rotas e regras de autorização anteriores foram preservadas.

A biblioteca efetivamente resolvida é Spring Security **7.1.1**, compatível com
o Spring Boot **4.1.1** do projeto. A referência a Security 4.x no pedido não
corresponde às dependências existentes; nenhuma versão foi alterada.

## Testes executados em 26/10/2026

| Execução | Resultado |
|---|---|
| `.\dev.ps1 test` | 11 testes de bloqueio (incluindo 5 repetições concorrentes), 16 de login e 3 de estrutura aprovados |
| `.\dev.ps1 persistence-write` | 1 teste aprovado em JVM PID 15060; bloqueio por duas chamadas reais a /login |
| `.\dev.ps1 persistence-read` | 1 teste aprovado em outra JVM, PID 18336; senha correta rejeitada após reinício |
| Maven `-DskipTests package` com cache local em D: | BUILD SUCCESS; JAR atualizado após os testes |

**32 testes efetivamente executados e aprovados, sem falhas ou erros.**
Na suíte normal, os dois métodos de persistência ficam ignorados até selecionar
write/read; cada comando de persistência executa um deles e ignora o outro.

Cobertura funcional:

- primeira falha, segunda falha e commits observados após HTTP 401;
- login correto rejeitado em conta bloqueada;
- AuthenticationManager e HTTP Basic rejeitam o bloqueio central;
- sucesso antes do limite, limpeza das datas e nova falha iniciando em 1;
- usuário inexistente sem criação de registro;
- contador e datas imutáveis em tentativas adicionais na conta bloqueada;
- Basic incorreto/correto não incrementa nem zera tentativas;
- Basic válido em conta desbloqueada mantém autenticação e regras de role;
- duas falhas simultâneas resultam exatamente em contador 2 e bloqueio, repetido 5 vezes;
- bloqueio, credenciais, roles e registros persistem entre duas JVMs;
- após reinício, correspondência BCrypt da senha correta confirmada e autenticação
  rejeitada via AuthenticationManager (LockedException), POST /login e HTTP Basic;
- regressão dos cenários 200/401/400, validação, ausência de sessão e senhas/hashes nos logs.

Os testes usam bancos isolados: H2 em memória para a suíte HTTP e arquivo em
`.local/persistence-check/` para reinícios. O banco normal não foi alterado:
seu SHA-256 permaneceu `138293443A3EFD5F9A85A4508BE5DB3862223E387F53646F9FCD752F76F5CB92`.
Logs completos estão em `.local/etapa3-tests.log`, `.local/etapa3-persistence-write.log`,
`.local/etapa3-persistence-read.log` e `.local/etapa3-package.log`.

## Ajustes e pendências

A expectativa antiga de que falhas repetidas não produzissem bloqueio foi substituída
pelo teste de falha seguida de sucesso e reinício do contador. Foi necessário limpar
as tentativas entre cenários no banco de testes, pois o estado agora é persistido.
Nenhuma regressão funcional restante nos testes anteriores.
Um import de AccountStatusUserDetailsChecker foi corrigido após a primeira compilação;
o pacote correto foi confirmado na biblioteca instalada.

Nenhuma pendência de validação desta etapa. Não existe desbloqueio automático nem
endpoint administrativo de desbloqueio. Não foram implementados troca de senha,
cadastro, listagem, métricas, Prometheus, Grafana ou Swagger. Sem novas instalações,
dependências, commits ou publicação. Cache, temporários e dados continuam na unidade D:.

Próxima etapa: gerenciamento de usuários e permissões, incluindo desbloqueio
administrativo, somente após autorização.
