# Etapa 5 — Métricas e observabilidade

## Resultado

Implementados dois Counters e um Gauge com Actuator, Micrometer e Prometheus Registry.
Sem mudanças na lógica transacional de autenticação/bloqueio, gerenciamento ou roles.
Nenhum servidor Prometheus externo, Grafana, Swagger ou funcionalidade REST extra.

## Fontes consultadas

A versão atual do README do professor foi lida diretamente do GitHub nas seções
Actuator e Métricas; também foi consultada a seção de configuração do Prometheus.
Foi seguida a abordagem de componente com Counter e injeção no controller:
[roteiro da disciplina](https://github.com/esensato/ms-2026-02#actuator).
A porta/endereço de gerenciamento e o matcher específico foram conferidos na
[documentação Spring Boot](https://docs.spring.io/spring-boot/reference/actuator/monitoring.html)
e na [API EndpointRequest do Boot 4](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/security/autoconfigure/actuate/web/servlet/EndpointRequest.html).

## Arquivos criados

- `src/main/java/br/edu/microservices/av02/LoginMetrics.java`;
- `src/test/java/br/edu/microservices/av02/LoginMetricsIntegrationTest.java`;
- `ETAPA5_VALIDACAO.md`.

## Arquivos modificados

- `pom.xml`: somente as dependências diretas spring-boot-starter-actuator e micrometer-registry-prometheus;
- `src/main/resources/application.properties`: listener local separado e exposição restrita;
- `src/main/java/br/edu/microservices/av02/LoginController.java`: registro do resultado após retorno do serviço;
- `src/main/java/br/edu/microservices/av02/SecurityConfig.java`: cadeia específica do Actuator e ordem explícita das duas cadeias;
- `src/test/java/br/edu/microservices/av02/LoginIntegrationTest.java`: porta de gerenciamento aleatória;
- `src/test/java/br/edu/microservices/av02/LoginBlockingIntegrationTest.java`: porta de gerenciamento aleatória;
- `src/test/java/br/edu/microservices/av02/StructureIntegrationTest.java`: porta de gerenciamento aleatória;
- `src/test/java/br/edu/microservices/av02/UserManagementIntegrationTest.java`: porta de gerenciamento aleatória;
- `src/test/java/br/edu/microservices/av02/PersistenceRestartTest.java`: porta aleatória e verificações de Counters/Gauge/exportação após reinício;
- `README.md`: semântica, nomes reais, coleta local e testes.

LoginService, UserService, initializer, schema.sql e dev.ps1 não foram alterados.
Versões mantidas: Spring Boot 4.1.1, Java alvo 17, JDK existente 21.0.6.
Micrometer Prometheus Registry 1.17.1 foi resolvido pelo gerenciamento de dependências.

## Indicadores e pontos de atualização

| Tipo | Micrometer (Java) | Prometheus confirmado por HTTP | Origem |
|---|---|---|---|
| Counter | av02.login.success | av02_login_success_total | LoginMetrics.recordLogin(true), chamado pelo controller após sucesso transacional |
| Counter | av02.login.failure | av02_login_failure_total | LoginMetrics.recordLogin(false), chamado pelo controller após autenticação inválida |
| Gauge | av02.users.locked | av02_users_locked | COUNT(*) em login_attempts WHERE locked=TRUE, a cada leitura |

Os três medidores são registrados na construção do componente, incluindo valor
inicial zero. Não há labels individuais, username, senha ou hash.
O Micrometer converte os pontos em underscores e acrescenta `_total` aos Counters.
Os nomes e tipos reais foram verificados em `/actuator/prometheus`.

O serviço já captura falhas de autenticação, persiste as tentativas e retorna
normalmente. A métrica é registrada uma única vez pelo controller depois desse
retorno/commit. Senha incorreta, usuário inexistente e conta bloqueada contam como
falha; JSON/campos inválidos que retornam 400 não chegam ao registro. HTTP Basic
e os demais endpoints não chamam esse componente de contagem. Se o filtro Basic
rejeitar uma requisição antes de processar o corpo de /login, ela não representa
uma tentativa processada pelo endpoint. Erros transacionais não são contados como
resultado 200/401 pelo controller.

Counters acumulam eventos desde a inicialização e reiniciam com o processo.
O Gauge consulta o estado persistente, diminui no desbloqueio e continua correto
após reinício. Não é um total acumulado de bloqueios. Nenhuma tabela de métricas
foi criada; coleta histórica por Prometheus fica para a próxima etapa.

## Actuator e segurança

```properties
management.server.address=127.0.0.1
management.server.port=9099
management.endpoints.web.exposure.include=prometheus
management.endpoints.web.discovery.enabled=false
```

Coleta: GET `http://127.0.0.1:9099/actuator/prometheus`, sem autenticação, somente
no listener local de gerenciamento. As APIs permanecem em 127.0.0.1:8080.
Uma SecurityFilterChain de ordem 1 seleciona os endpoints reais do Actuator e
permite somente o GET de Prometheus; a cadeia anterior fica em ordem 2, sem
alterações em suas permissões. Outros endpoints administrativos não são expostos.
Nos testes, API e gerenciamento usam portas aleatórias distintas, ligadas ao loopback.

## Testes executados em 26/10/2026

| Execução | Resultado |
|---|---|
| Compilação com as novas dependências | BUILD SUCCESS com release 17 |
| `.\dev.ps1 test` | 15 testes de métricas + 74 testes anteriores aprovados |
| `.\dev.ps1 persistence-write` | 1 aprovado, JVM PID 9912 |
| `.\dev.ps1 persistence-read` | 1 aprovado em outra JVM, PID 6044 |
| Maven `-DskipTests package`, após os testes | BUILD SUCCESS; JAR atualizado |

**91 testes efetivamente executados e aprovados, sem falhas ou erros.**
Os dois métodos de persistência são ignorados na suíte normal; os comandos
write/read executam um por vez, como nas etapas anteriores.

Os testes de métricas comparam deltas dos Counters, sem limpar/resetar medidores
ou depender da ordem de execução. Usam bancos H2 isolados e verificam tanto a
API Micrometer quanto os valores realmente exportados por HTTP.

Cobertura validada:

- sucesso +1 apenas no Counter de válidos;
- senha incorreta e usuário inexistente +1 no Counter de inválidos;
- segunda falha +1 inválido e Gauge de bloqueados aumenta;
- conta já bloqueada +1 inválido, sem aumentar contador persistido ou Gauge;
- Basic válido, inválido, ausência de autenticação e role insuficiente sem contar login;
- cadastro, troca de senha e desbloqueio sem incrementar Counters;
- seis formatos de requisição inválida 400 sem incrementar Counters;
- desbloqueio reduz Gauge, inclusive com outra conta ainda bloqueada;
- mudanças feitas diretamente nas fixtures do banco refletem imediatamente no Gauge;
- dois sucessos simultâneos: sucesso +2, falha inalterada;
- duas falhas simultâneas: falha +2 e exatamente um usuário bloqueado;
- exportação das três métricas com nomes/tipos reais e sem labels individuais;
- endpoint na porta de gerenciamento, inacessível na porta da API;
- discovery, health, env, configprops, heapdump e metrics não expostos;
- APIs de negócio não disponibilizadas no listener de gerenciamento;
- regressão completa de autenticação, autorização, bloqueio, gerenciamento e persistência;
- nova JVM começa com Counters zero e Gauge 2, correspondente aos registros bloqueados persistidos.

## Amostras reais

Extraída no teste HTTP após um sucesso e duas falhas que bloquearam uma conta:

```text
av02_login_failure_total 2.0
av02_login_success_total 1.0
av02_users_locked 1.0
```

Na segunda JVM, antes de qualquer login, com duas fixtures bloqueadas no banco:

```text
av02_login_failure_total 0.0
av02_login_success_total 0.0
av02_users_locked 2.0
```

Arquivos locais com as amostras: `.local/etapa5-prometheus-sample.prom` e
`.local/etapa5-restart-prometheus-sample.prom`. São valores de bancos de teste,
não do banco normal. O endpoint também exporta as métricas técnicas padrão.
Logs: `.local/etapa5-compile.log`, `.local/etapa5-tests.log`,
`.local/etapa5-persistence-write.log`, `.local/etapa5-persistence-read.log` e
`.local/etapa5-package.log`.

## Preservação, limitações e próxima etapa

Nenhuma regressão ou pendência de validação encontrada. Nenhuma alteração na AV01.
O banco normal não foi utilizado; seu SHA-256 permaneceu
`138293443A3EFD5F9A85A4508BE5DB3862223E387F53646F9FCD752F76F5CB92`.
Downloads de dependências, cache Maven, temporários, logs e dados gerados ficaram
dentro de AV02, em D:. Não foram instaladas ferramentas externas.

A coleta está disponível somente no loopback. Os Counters são por processo e
não mantêm histórico entre reinícios; o servidor Prometheus cuidará da coleta
histórica na Etapa 6. Não houve commit ou publicação.
Próxima etapa: servidor Prometheus e Grafana, somente após autorização.
