# AV-02 — Etapa 7: OpenAPI e Swagger UI

Concluída em **26/10/2026**, horário de São Paulo. Alterações locais na AV02;
sem commit ou push nesta etapa. A Etapa 8 não foi iniciada.

## 1. Implementação

Documentados os cinco endpoints existentes com informações gerais, descrições,
schemas de entrada e saída, exemplos fictícios, parâmetros de rota, códigos HTTP
e esquema HTTP Basic. Não foram alteradas as regras de negócio, métricas ou roles.

Metadados: título **AV-02 — Segurança e Observabilidade**, descrição
**API REST de gerenciamento de usuários, autenticação e controle de bloqueios**,
versão **1.0.0**. Especificação gerada em OpenAPI 3.1.

## 2. Dependência e compatibilidade

Adicionado `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1`, seguindo a
seção Documentação Endpoints do professor. A matriz oficial relaciona Boot 4.1.x
com Springdoc 3.1.x–3.2.x. A compatibilidade da versão escolhida também foi
comprovada por compilação, testes HTTP e execução real da interface.

Mantidos Spring Boot **4.1.1**, Java alvo **17** e JDK **21** existente.
Downloads Maven, cache, build, logs e temporários permaneceram em D:, dentro
de AV02. Não houve instalação global ou mudança da versão do Spring Boot.

## 3. Arquivos criados e modificados

Criados:

- `src/main/java/br/edu/microservices/av02/OpenApiConfig.java`: metadados e Basic.
- `src/test/java/br/edu/microservices/av02/OpenApiIntegrationTest.java`: 7 testes HTTP reais.
- `docs/openapi.json`: cópia da especificação gerada pelo servidor validado.
- `docs/evidence/swagger-ui.json`: resultados da execução pela interface.
- `docs/evidence/swagger-ui.png`: screenshot real, sem autorização preenchida.
- `docs/evidence/integration.json`: verificação de Prometheus e Grafana nesta etapa.
- `ETAPA7_VALIDACAO.md`: este relatório.

Modificados:

- `pom.xml`: dependência Springdoc 3.1.1.
- `SecurityConfig.java`: acesso público GET restrito aos recursos da documentação.
- `LoginController.java`: anotações da operação e campos de entrada.
- `UserController.java`: anotações das quatro operações e campos de entrada.
- `application.properties`: autorização não persistida pelo Swagger, validador
  externo desabilitado e Actuator excluído da especificação.
- `README.md`: acesso, exemplos, roles, comandos e captura do Swagger.

Não modificados: serviços de negócio, schema SQL, inicializador de contas,
testes anteriores, scripts de observabilidade, métricas, dashboard e AV01.
A cópia Git em `.local/publish-etapa6` não recebeu estas alterações.

## 4. Endereços e acesso

| Recurso | URL efetiva | Resultado |
|---|---|---|
| Swagger UI | http://127.0.0.1:8080/swagger-ui/index.html | 200, interface funcional |
| OpenAPI JSON | http://127.0.0.1:8080/v3/api-docs | 200, JSON válido |
| OpenAPI YAML | http://127.0.0.1:8080/v3/api-docs.yaml | 200 |
| Atalho Swagger | http://127.0.0.1:8080/swagger-ui.html | 302 para `/swagger-ui/index.html` |
| Configuração Swagger | http://127.0.0.1:8080/v3/api-docs/swagger-config | 200 |
| Actuator | http://127.0.0.1:9099/actuator/prometheus | 200, listener preservado |

A regra libera somente GET para `/v3/api-docs`, `/v3/api-docs.yaml`,
`/v3/api-docs/swagger-config`, `/swagger-ui.html` e `/swagger-ui/**`.
Outros caminhos continuam sujeitos às permissões anteriores; não foi adicionado
`permitAll` genérico à API. A cadeia específica do Actuator permaneceu intacta.

O esquema `basicAuth` é HTTP Basic, sem OAuth2 ou scopes. As três operações
protegidas o referenciam individualmente. Não há requisito global de autenticação,
portanto login e troca de senha aparecem públicos. As roles são descritas nas
operações e continuam sendo aplicadas pelo Spring Security.

## 5. Cinco endpoints documentados

| Método e rota | Entrada | Acesso | Respostas |
|---|---|---|---|
| `POST /login` | `username`, `senha` | Público | 200, 400, 401 |
| `PUT /trocarsenha/{userid}` | `atual`, `nova` | Público, valida a senha atual | 204, 400, 401 |
| `POST /` | `username`, `senha` | ROLE_MANUTENCAO / Basic | 201, 400, 401, 403, 409 |
| `PUT /unlock/{userid}` | parâmetro de rota | ROLE_ADMIN / Basic | 204, 401, 403, 404 |
| `GET /` | sem corpo | ROLE_MANUTENCAO / Basic | 200, 401, 403 |

`userid` corresponde ao **username**, não a um identificador numérico.
Os campos de entrada estão marcados como obrigatórios. Senhas têm formato
`password`, acesso `writeOnly` e exemplos expressamente fictícios. O limite de
100 caracteres do username de cadastro e o limite BCrypt de 72 bytes UTF-8
na criação/troca foram documentados conforme o código existente.

A listagem existente devolve **array de objetos contendo somente username**:
`[{"username":"usuario-exemplo"}]`. Esse formato foi preservado e documentado,
sem inventar um array de strings ou objetos com senhas. Demais respostas de
negócio não têm corpo. O documento não inclui entidades JDBC ou UserDetails.

## 6. Novos testes automatizados

`OpenApiIntegrationTest`: **7 aprovados**, sem falhas, usando servidor Spring Boot
real em portas aleatórias, H2 em memória próprio e credenciais aleatórias.

1. JSON válido, metadados e exatamente cinco operações em quatro caminhos.
2. Todos os códigos HTTP e ausência de corpo nas respostas correspondentes.
3. Basic HTTP sem scopes; operações públicas/protegidas e roles descritas.
4. Schemas, campos obrigatórios, writeOnly, formatos, limites e parâmetros de rota.
5. Saída da listagem somente com username; ausência das credenciais e hashes
   armazenados no banco isolado, sem estruturas internas de autenticação.
6. YAML, configuração, HTML, CSS e JavaScript do Swagger acessíveis; atalho 302.
7. Recursos fora da documentação e POST no JSON continuam protegidos;
   Actuator continua disponível no listener de gerenciamento, sem OpenAPI ali.

Log da execução específica: `.local/etapa7-openapi-tests.log`.

## 7. Testes funcionais pela interface real

Swagger aberto no Microsoft Edge real, com perfil e cache isolados em D:.
Os cinco endpoints, descrições, esquemas e cadeados de segurança foram conferidos.
As chamadas abaixo foram feitas pelo botão **Execute** do Swagger, não substituídas
por chamadas diretas. Foram conferidos tanto o status da resposta na rede quanto
o código exibido na tabela de resposta do Swagger.

| Cenário | Resultado observado |
|---|---|
| Login com credenciais fictícias válidas | 200 |
| Login com usuário fictício inexistente | 401 |
| Authorize com manutenção e `GET /` | 200 |
| Mesma autorização em `PUT /unlock/usuario-exemplo` | 403 |

Operações executadas exclusivamente contra
`.local/observability/app/av02-demo.mv.db`. Antes de gerar eventos, foi conferido
o PID, horário de início e argumento de datasource da aplicação registrada.
Nenhuma tentativa foi feita no banco principal.

Após os testes, a página foi recarregada para limpar a autorização e os corpos
preenchidos; a captura real foi inspecionada visualmente e não contém credenciais.
Os arquivos de evidência guardam somente operações, status e verificações.
Senha e Authorization usados durante o teste não foram gravados nesses arquivos.

Evidências: `docs/evidence/swagger-ui.json` e `docs/evidence/swagger-ui.png`.
As tentativas feitas ao ajustar a automação também geraram eventos no banco
isolado; não se deve interpretar o número final dos Counters como apenas as
quatro chamadas da execução final. Ajustes de seletores e de tratamento da
caixa nativa Basic foram necessários na automação, sem alterações na API.

## 8. Regressão, persistência e observabilidade

**98 cenários efetivamente aprovados: os 91 anteriores e 7 novos.**

| Execução | Resultado |
|---|---|
| `./dev.ps1 package` | BUILD SUCCESS; 98 descobertos, 96 executados e aprovados, 2 fases de persistência ignoradas nessa execução |
| `./dev.ps1 persistence-write` | escrita aprovada em uma JVM |
| `./dev.ps1 persistence-read` | leitura aprovada em outra JVM |

Logs: `.local/etapa7-regression.log`, `.local/etapa7-persistence-write.log` e
`.local/etapa7-persistence-read.log`. A suíte anterior continua sem dependência
de Prometheus, Grafana ou navegador.

Autenticação, HTTP Basic, roles, BCrypt, bloqueio, desbloqueio, troca de senha,
cadastro, listagem, persistência e métricas continuaram passando nos testes.

Depois do reinício da stack com o novo JAR, a integração externa também passou:
target Prometheus **UP**, fonte Grafana **OK**, intervalo de 5s e três consultas
instantâneas preservadas. Valores finais comparados: **5 válidos, 4 inválidos,
1 bloqueado**, iguais no Actuator, Prometheus e Grafana. A evidência numérica
está em `docs/evidence/integration.json`.

O hash do dashboard JSON permaneceu
`DD7F8F2C572743E5DD4FBAFCB8117C89B8834DE461D42E65C57EAF9903512C2C`.
O banco principal permaneceu com SHA-256
`138293443A3EFD5F9A85A4508BE5DB3862223E387F53646F9FCD752F76F5CB92`.

## 9. Reprodução e limitações

No PowerShell 7, em AV02 na unidade D::

```powershell
# Se a stack estiver ativa, parar antes de substituir o JAR no Windows:
.\observability\stack.ps1 stop
.\dev.ps1 package
.\dev.ps1 persistence-write
.\dev.ps1 persistence-read
Remove-Item Env:AV02_RESTART_TEST_PASSWORD
.\observability\stack.ps1 start
# Abrir http://127.0.0.1:8080/swagger-ui/index.html
```

Use **Try it out > Execute** para operações públicas e **Authorize** com uma conta
fictícia do banco isolado para as protegidas. Para repetir a inspeção manual de
schemas, expanda cada operação e a seção Schemas. Nunca teste credenciais reais
ou gere falhas no banco principal para essa demonstração.

O build inicial passou pelos testes, mas falhou ao substituir o JAR ainda aberto
pela aplicação no Windows. Após encerrar a stack pelo script próprio, o build
completo passou e a stack foi reiniciada com sucesso.

Swagger é uma ferramenta de execução real: a tela pode mostrar o corpo enviado
e o cabeçalho Basic ao executar uma requisição. Não compartilhar essa tela com
credenciais preenchidas. `persist-authorization=false` não transforma os dados
da requisição em conteúdo oculto; limpe a autorização e recarregue antes de capturar.

Sem pendências funcionais da Etapa 7. Não foram criados novos endpoints de negócio,
JWT/OAuth2, frontend, serviços adicionais ou mudanças no dashboard. A documentação
foi adicionada somente ao listener local da aplicação. Alterações não publicadas.

## 10. Próxima etapa

**Etapa 8 — Revisão e entrega**, somente após autorização do usuário.

Fontes consultadas:

- [Documentação de endpoints do professor](https://github.com/esensato/ms-2026-02#documentação-endpoints).
- [Enunciado oficial](https://github.com/esensato/ms-2026-02/blob/main/MS-AV-02-Seguran%C3%A7a-Observabilidade.md).
- [Springdoc e matriz de compatibilidade](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot).
