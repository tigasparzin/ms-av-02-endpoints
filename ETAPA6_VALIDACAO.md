# AV-02 — Etapa 6: relatório de validação

Validação concluída em 26/10/2026. Escopo: Prometheus, Grafana, demonstração
isolada, exportação oficial, screenshot real e regressão. Etapa 7 não iniciada.

## 1. Implementação e diagnóstico

Prometheus e Grafana executam como processos nativos portáteis do Windows.
Não foram encontradas instalações existentes dessas ferramentas; Docker estava
instalado, mas seu daemon não estava operacional. As quatro portas estavam livres.
A alternativa nativa manteve o Actuator em loopback e dispensou mudanças de rede,
serviços Windows e configurações globais. Nenhum contêiner ou volume foi alterado.

Foram preservados o código Java, as dependências Maven, as configurações de
segurança e as métricas da Etapa 5. A AV01 não foi modificada.

## 2. Ferramentas e versões

| Ferramenta | Versão / utilização |
|---|---|
| Spring Boot | 4.1.1 existente, Java alvo 17 |
| JDK | 21 existente |
| Maven | 3.9.9 existente |
| Prometheus | 3.13.5 LTS, Windows amd64 portátil |
| Grafana OSS | 13.2.4, Windows amd64 portátil |
| PowerShell | 7.6.5 existente |
| Node.js | 24.22.1 existente, automação CDP sem dependências adicionais |
| Microsoft Edge | navegador existente, renderização e screenshot reais |

Downloads oficiais com SHA-256 fixado e verificado em `observability/install.ps1`.
Binários, downloads, temporários, caches, perfil de navegador, logs e dados ficam
sob `AV02/.local/observability`, na unidade D:. Java e navegador existentes em C:
foram reutilizados; não houve instalação adicional nessa unidade.

## 3. Arquivos criados e modificados

Modificado: `README.md`, incluindo comandos e links dos artefatos da Etapa 6.

Criados nesta etapa:

- `observability/install.ps1`: obtenção, hashes e extração das distribuições.
- `observability/prometheus.yml`: coleta local do job `av02`.
- `observability/grafana.ini` e `observability/datasource.yml`: Grafana e fonte Prometheus.
- `observability/stack.ps1`: iniciar, consultar e parar os processos próprios.
- `observability/common.ps1`: consultas e verificações compartilhadas de integração.
- `observability/demo.ps1`: eventos fictícios pela API, usando banco separado.
- `observability/verify.ps1`: comparação das três fontes e validação da configuração.
- `observability/browser.mjs`: interface real, exportação, reimportação visual,
  refresh automático e captura pelo Edge/CDP.
- `observability/import-check.ps1`: importação e remoção da própria cópia temporária.
- `observability/dashboard/av02-dashboard.json`: exportação oficial portátil.
- `observability/README.md`: instalação, execução, demonstração, importação e encerramento.
- `observability/evidence/*.json`: resultados numéricos e provas das operações.
- `observability/evidence/dashboard-grafana.png`: captura real de entrega.
- `ETAPA6_VALIDACAO.md`: este relatório.

Arquivos locais de runtime não são entregáveis: `.local` já é ignorado pelo Git.
No encerramento da validação, ainda não havia commit ou publicação. A publicação
foi autorizada posteriormente em https://github.com/tigasparzin/ms-av-02-endpoints.
O histórico inicial desse repositório foi preservado na preparação da entrega.

## 4. Portas e endereços finais

| Serviço | Endereço |
|---|---|
| Aplicação com H2 de demonstração | http://127.0.0.1:8080 |
| Actuator | http://127.0.0.1:9099/actuator/prometheus |
| Prometheus / targets | http://127.0.0.1:9090/targets |
| Grafana | http://127.0.0.1:3000/d/av02-seguranca-observabilidade |

Todos os listeners estão limitados a `127.0.0.1`. O Actuator continua expondo
somente Prometheus. Grafana permite leitura local anônima com perfil Viewer.

## 5. Coleta Prometheus

Job `av02`, target `127.0.0.1:9099`, caminho `/actuator/prometheus`, intervalo
de coleta **5 segundos**, timeout de 4 segundos. Target **UP**, sem erro de coleta.
Fonte **Prometheus AV02**, UID `av02-prometheus`, URL `http://127.0.0.1:9090`,
health **OK** no Grafana. Retenção TSDB local: 7 dias.

## 6. Consultas PromQL

```promql
av02_login_success_total{job="av02",instance="127.0.0.1:9099"}
av02_login_failure_total{job="av02",instance="127.0.0.1:9099"}
av02_users_locked{job="av02",instance="127.0.0.1:9099"}
```

Consultas instantâneas, `range=false`, uma série explícita por indicador. A validação
rejeita séries ausentes ou duplicadas. Não há `rate()`, soma de amostras temporais,
transformação que esconda erros ou substituição artificial por zero.

## 7. Painéis

Dashboard **AV-02 — Segurança e Observabilidade**, atualização automática **5s**.
Três painéis Stat, numéricos e sem casas decimais: **Logins válidos** (verde),
**Logins inválidos** (vermelho), **Usuários bloqueados** (laranja).
Os dois primeiros mostram Counters acumulados desde o início da aplicação;
o terceiro mostra o Gauge atual de bloqueios persistidos no H2.

## 8. Valores e eventos reais

Cada linha abaixo foi comparada no Actuator, no Prometheus e na fonte Grafana,
aguardando convergência da coleta. Evidência: `observability/evidence/integration.json`.

| Evento | Válidos | Inválidos | Bloqueados |
|---|---:|---:|---:|
| Baseline da demonstração | 0 | 0 | 0 |
| Login válido | 1 | 0 | 0 |
| Primeira senha incorreta | 1 | 1 | 0 |
| Segunda senha incorreta / bloqueio | 1 | 2 | 1 |
| Login em conta bloqueada | 1 | 3 | 1 |
| Desbloqueio administrativo | 1 | 3 | 0 |
| HTTP Basic válido e inválido | 1 | 3 | 0 |
| Eventos finais representativos | 2 | 5 | 1 |
| Estado da captura final, após duas sondagens de refresh | 2 | 7 | 1 |

A última sondagem alterou os painéis de **2/6/1 para 2/7/1** automaticamente.
Foram observadas 6 requisições da fonte de dados antes e 9 depois, sem clique
manual de atualização. Prova: `observability/evidence/browser-refresh.json`.
O estado final **2/7/1** coincide nas três fontes em `final-verification.json`.

## 9. JSON exportado e importação

Arquivo: `observability/dashboard/av02-dashboard.json`.
Obtido pela interface oficial **Export > Export as code > Advanced options >
Classic > Share dashboard with another instance > Download file**.
O arquivo de entrega é uma cópia idêntica aos bytes baixados, SHA-256:

```text
DD7F8F2C572743E5DD4FBAFCB8117C89B8834DE461D42E65C57EAF9903512C2C
```

O JSON utiliza entrada portátil `DS_PROMETHEUS_AV02`, sem caminho da máquina.
Foi reimportado pela API oficial em uma cópia temporária com UID exclusivo.
Consultas e fonte foram conferidas; no navegador real os painéis mostraram
**2/5/1**, sem `No data` ou erros. A cópia temporária foi removida após o teste;
o dashboard principal foi preservado. Evidências: `export-verification.json`,
`import-verification.json` e `import-browser.json`.

Para importar em outro Grafana, crie uma fonte Prometheus e selecione-a na tela de
importação do JSON. Os seletores job/instance devem corresponder ao destino.

## 10. Screenshot real

Arquivo: `observability/evidence/dashboard-grafana.png`, **1600 × 650 pixels**.
Captura do Edge renderizando o Grafana real, inspecionada visualmente. Mostra o
título, os três painéis, valores **2/7/1** e atualização de 5s. Não inclui senhas,
hashes ou dados pessoais. A imagem não foi simulada.

## 11. Testes de integração e isolamento

- Aplicação e Actuator acessíveis; Prometheus UP; fonte Grafana OK.
- Todos os eventos da tabela produziram as alterações exigidas.
- HTTP Basic não alterou os Counters.
- Painéis reais numéricos, sem `No data` ou erro de consulta.
- Refresh automático comprovado pelo DOM e pelas requisições do navegador.
- Exportação oficial e reimportação funcional, inclusive renderização real.
- Os três processos foram encerrados e iniciados novamente pelos scripts.
  Depois disso, as três fontes mostraram **0/0/1**: Counters reiniciados e bloqueio
  persistido. Evidência: `observability/evidence/after-restart.json`.
- Após reinício, nova demonstração e screenshot confirmaram funcionamento completo.
- A demonstração verifica PID, horário de criação e argumento de datasource antes
  de operar. H2 utilizado: `.local/observability/app/av02-demo.mv.db`.

O SHA-256 do banco normal `data/av02.mv.db` permaneceu igual ao diagnóstico inicial:

```text
138293443A3EFD5F9A85A4508BE5DB3862223E387F53646F9FCD752F76F5CB92
```

Credenciais fictícias são geradas localmente e protegidas por DPAPI em `.local`.
Não estão no JSON, screenshot ou evidências. Logs e evidências evitam corpos
de requisição com credenciais e respostas com hashes.

## 12. Regressão

**91 testes efetivamente aprovados, sem falhas ou erros.**

| Comando | Resultado |
|---|---|
| `./dev.ps1 test` | 91 descobertos; 89 executados e aprovados; 2 fases de persistência ignoradas nesta execução |
| `./dev.ps1 persistence-write` | fase de escrita aprovada em processo Maven separado |
| `./dev.ps1 persistence-read` | fase de leitura aprovada em novo processo Maven |

As duas fases executadas separadamente completam os 91 cenários. Logs em
`.local/etapa6-tests.log`, `.local/etapa6-persistence-write.log` e
`.local/etapa6-persistence-read.log`. Login, roles, BCrypt, bloqueio, desbloqueio,
cadastro, troca de senha, listagem, persistência e Micrometer permanecem cobertos
pela suíte existente, que não passou a depender de serviços externos.

## 13. Pendências e limitações

Nenhuma pendência funcional da Etapa 6. Serviços locais permanecem em execução
para inspeção. Os valores podem aumentar ao repetir a demonstração; os Counters
reiniciam com a aplicação, enquanto bloqueios persistem no banco isolado.

O primeiro início do Grafana exigiu mais tempo para migrações e plugins;
o timeout de prontidão foi ajustado para 180 segundos e o reinício foi validado.
A captura usa Edge/CDP porque a ferramenta de controle gráfico estava indisponível.
A automação usa PowerShell 7; a política de execução é ajustada somente no
processo filho de verificação, sem alterar a configuração global do Windows.

Instalação voltada à execução local de avaliação. Swagger/OpenAPI, publicação,
novos endpoints e serviços adicionais não foram implementados. Próxima etapa:
**Etapa 7 — OpenAPI/Swagger**, somente mediante autorização.

## 14. Reprodução

No PowerShell 7, dentro da pasta AV02 em D:, com as portas livres:

```powershell
# Compilar somente se o JAR não existir ou o código tiver mudado:
.\dev.ps1 package
.\observability\install.ps1
.\observability\stack.ps1 start
.\observability\stack.ps1 status
.\observability\demo.ps1
.\observability\verify.ps1 -EvidenceName verification
# Abrir http://127.0.0.1:3000/d/av02-seguranca-observabilidade
# Captura opcional; exige Node.js 22+ e Edge disponíveis:
node .\observability\browser.mjs screenshot
# Encerrar somente os processos registrados, preservando os dados:
.\observability\stack.ps1 stop
```

O procedimento detalhado, a importação e os pré-requisitos estão em
`observability/README.md`. Para apenas inspecionar a execução já ativa, use `status`
e abra o endereço do dashboard; não tente iniciar uma segunda stack.

Fontes: [enunciado do professor](https://github.com/esensato/ms-2026-02/blob/main/MS-AV-02-Seguran%C3%A7a-Observabilidade.md),
[README do professor](https://github.com/esensato/ms-2026-02),
[Prometheus](https://prometheus.io/docs/prometheus/latest/installation/) e
[exportação oficial Grafana](https://grafana.com/docs/grafana/latest/visualizations/dashboards/share-dashboards-panels/#export-a-dashboard-as-code).
