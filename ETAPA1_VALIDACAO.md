# Etapa 1 — Validação executada em 26/10/2026

## Escopo

Estrutura inicial implementada somente em AV02. AV01 e ENUNCIADO.md preservados.
Sem controllers, lógica de login/bloqueio, métricas, dashboard, commits ou publicação.

## Ambiente confirmado

- Spring Boot 4.1.1 e dependências gerenciadas pelo seu BOM.
- JDK 21.0.6; compilação de produção e testes com `--release 17`.
- Maven 3.9.9 existente em D:.
- Dependências, temporários e bancos criados dentro de AV02, em D:.

## Resultados

| Comando / execução | Resultado |
|---|---|
| `./dev.ps1 test` | 3 testes de integração aprovados; 2 métodos de persistência ignorados propositalmente até selecionar a fase |
| `./dev.ps1 persistence-write` | 1 teste aprovado em JVM PID 9676; grava usuários BCrypt e fixture de tentativas no H2 em arquivo |
| `./dev.ps1 persistence-read` | 1 teste aprovado em outra JVM, PID 20380; reabre o banco e compara os dados |
| Maven `-DskipTests package`, com repositório em `.local/m2/repository` | BUILD SUCCESS; testes já tinham sido executados nos comandos anteriores |
| JAR executável, PID 5720, porta aleatória | Inicialização normal concluída, HTTP 401 sem credenciais e `data/av02.mv.db` criado |
| H2 Shell após encerrar o JAR, com `IFEXISTS=TRUE` | Reabriu o banco normal; as três tabelas existem e estão vazias, como esperado sem senhas de inicialização |

Total: **5 verificações de integração aprovadas**, além da inicialização do JAR.
O servidor iniciado para validação foi encerrado ao final.

Os testes verificaram:

- criação de `usuarios`, `authorities` e `login_attempts`, sem tabela `users`;
- BCrypt com prefixo `{bcrypt}`, comprimento 68 e correspondência com senhas aleatórias em memória;
- roles ADMIN e MANUTENCAO distintas;
- cadastro, existência, leitura, atualização, troca de senha e exclusão via JdbcUserDetailsManager;
- atualização e exclusão das authorities correspondentes;
- 401 sem credenciais ou com senha errada, e 403 para admin sem MANUTENCAO;
- preservação de hashes, usuário desabilitado, role e registro de tentativas entre JVMs;
- preservação dos três timestamps da fixture;
- inicialização repetida sem sobrescrever credenciais, mesmo recebendo outras senhas de inicialização.

O banco de persistência dos testes fica em `.local/persistence-check/`;
o banco normal fica em `data/`. A fixture de bloqueio é somente dado de teste.
O teste HTTP verifica a segurança inicial e não uma implementação de GET /.

## Ocorrências resolvidas

- Rede e loopback foram bloqueados pelo sandbox. Downloads e testes HTTP foram
  executados com permissão fora dele, mantendo os arquivos em D:.
- A consulta `userExists` foi corrigida para retornar `COUNT(*)`, como exige a
  versão efetivamente resolvida do JdbcUserDetailsManager.

## Pendências

Nenhuma falha de validação restante para a Etapa 1.
Para uso interativo das contas locais, fornecer as senhas descartáveis pelas
variáveis descritas no README. O teste do JAR sem essas variáveis criou o banco
normal vazio; as contas dos testes automatizados ficam em bancos separados.

Próxima etapa: POST /login, exclusivamente após autorização.
