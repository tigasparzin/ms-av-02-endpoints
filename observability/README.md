# Prometheus e Grafana — execução local da AV02

## Pré-requisitos e isolamento

Windows x64, PowerShell 7, JDK 21 e JAR da AV02. Os caminhos existentes de Java
e Maven seguem o script `dev.ps1`. Não é necessário Docker. A captura automatizada
usa o Edge já instalado e Node.js 22 ou superior, com WebSocket nativo.

As ferramentas portáteis são Prometheus **3.13.5 LTS** e Grafana OSS **13.2.4**.
Os downloads oficiais e SHA-256 estão fixados em `install.ps1` e foram conferidos
nas páginas de [Prometheus](https://prometheus.io/download/) e
[Grafana OSS](https://grafana.com/grafana/download?edition=oss&platform=windows).

Execute todos os comandos a partir da pasta AV02 na unidade D:.
As portas 8080, 9099, 9090 e 3000 devem estar livres. O script verifica isso e
nunca encerra outro serviço para liberar portas. Não execute `dev.ps1 run`
simultaneamente com a stack, pois ambos usam a porta 8080.

## Instalar, iniciar e abrir

```powershell
# Somente se o JAR ainda não estiver compilado ou o Java tiver sido alterado:
.\dev.ps1 package

# Idempotente: verifica hashes e reutiliza arquivos já instalados.
.\observability\install.ps1
.\observability\stack.ps1 start
.\observability\stack.ps1 status
```

No primeiro início, o Grafana cria seu SQLite e carrega plugins incluídos na
distribuição; isso demora mais que os próximos inícios. O script aguarda até
180 segundos por serviço. Se houver falha, consulte os logs em
`.local/observability/logs` e use `status` antes de tentar iniciar novamente.

| Serviço | Endereço |
|---|---|
| API com banco de demonstração | http://127.0.0.1:8080 |
| Actuator, somente métricas | http://127.0.0.1:9099/actuator/prometheus |
| Prometheus/targets | http://127.0.0.1:9090/targets |
| Dashboard Grafana | http://127.0.0.1:3000/d/av02-seguranca-observabilidade |

Todos os listeners ficam em **127.0.0.1**, sem abrir acesso na rede.
Prometheus acessa o Windows diretamente; não há tradução de localhost por contêiner.

## Onde ficam os arquivos locais

Binários, arquivos baixados, caches, temporários, perfis Edge, logs e dados ficam
em `.local/observability/`, ignorado pelo Git. O script usa especificamente:

- `app/av02-demo.mv.db`: H2 de demonstração, separado de `data/av02.mv.db`;
- `prometheus-data/`: histórico TSDB, com retenção de 7 dias;
- `grafana-data/`: SQLite e estado do Grafana;
- `grafana-plugins/`: diretório local de plugins;
- `provisioning/` e `dashboards/`: configuração resolvida para esta execução;
- `credentials.clixml`: credenciais fictícias criptografadas pelo DPAPI do Windows;
- `processes.json`: PIDs e horários de criação dos processos iniciados pelo script.

As senhas são geradas localmente, não são exibidas e só são descriptografadas em
memória para os scripts/variáveis dos processos filhos. O CLI-XML é específico
do usuário Windows e não deve ser compartilhado ou versionado. Não remova esse
arquivo isoladamente dos bancos de demonstração, pois os hashes das contas já
criadas dependem dessas credenciais. Nenhuma senha real é usada.
O Grafana permite leitura anônima local como Viewer; alterações automáticas usam
o administrador fictício local protegido. Cadastro público de contas Grafana e
downloads automáticos de plugins foram desabilitados.

## Fonte de dados e consultas

`datasource.yml` provisiona **Prometheus AV02**, UID `av02-prometheus`, apontando
para `http://127.0.0.1:9090`. Não há senha no arquivo. O job `av02` em
`prometheus.yml` coleta o Actuator a cada 5 segundos.

Os três painéis Stat usam consultas **instantâneas**, com atualização de 5 segundos:

```promql
av02_login_success_total{job="av02",instance="127.0.0.1:9099"}
av02_login_failure_total{job="av02",instance="127.0.0.1:9099"}
av02_users_locked{job="av02",instance="127.0.0.1:9099"}
```

O seletor fixa um único job/target, e a verificação exige exatamente uma série por
métrica. Não há soma de séries duplicadas, `rate()`, médias de janela ou fallback
para zero. Falta de dados e erro de consulta devem aparecer e interromper a
verificação. Os Counters são totais desde o início da aplicação e reiniciam junto
com ela. O Gauge é o estado atual persistido. O histórico continua no Prometheus,
mas os painéis consultam o valor atual e não escondem reinícios com reduções temporais.

## Gerar eventos e validar

```powershell
.\observability\demo.ps1
.\observability\verify.ps1 -EvidenceName verification
```

O primeiro script usa somente a API da aplicação que a stack iniciou com H2
isolado. Ele prepara duas contas comuns de demonstração e comprova:
login válido, primeira falha, segunda falha/bloqueio, tentativa adicional em conta
bloqueada, desbloqueio e Basic válido/inválido sem mudar os Counters. Ao final,
deixa valores representativos e uma conta de demonstração bloqueada.

Antes das operações de integração, o script confere o PID e horário do processo
registrado e seu argumento de datasource, recusando um banco diferente do H2
isolado de demonstração.

Cada passo compara Actuator, consulta Prometheus e resposta da fonte de dados
Grafana, aguardando a coleta de 5 segundos. Senhas, hashes e listagens de usuários
não são gravados nas evidências. `evidence/integration.json` contém somente etapas,
datas e os três valores. Repetir a demonstração soma novos eventos nos Counters;
não apaga os dados existentes nem zera métricas artificialmente.

## Encerrar e reiniciar

```powershell
.\observability\stack.ps1 stop
.\observability\stack.ps1 start
.\observability\verify.ps1 -EvidenceName after-restart
```

`stop` encerra somente os PIDs registrados, conferindo o horário de criação para
evitar atingir um PID reutilizado. Nenhum banco ou volume é excluído. Após reinício,
os Counters da aplicação começam em zero; o Gauge mantém os bloqueios do H2.
Os diretórios de dados do Prometheus e do Grafana são reutilizados.

## Exportar, importar e capturar

O dashboard de entrega está em `dashboard/av02-dashboard.json`. A exportação
oficial pela interface segue a [documentação Grafana](https://grafana.com/docs/grafana/latest/visualizations/dashboards/share-dashboards-panels/#export-a-dashboard-as-code):

1. Abra o dashboard e o botão **Export**.
2. Selecione **Export as code** e expanda **Advanced options**.
3. Selecione o modelo **Classic** e habilite **Share dashboard with another instance**.
4. Clique em **Download file** e salve o JSON na pasta `dashboard`.

Para importar em outro Grafana: crie uma fonte de dados Prometheus apontando para
o servidor usado; abra **Dashboards > New > Import**, envie o JSON e selecione essa
fonte quando solicitado. O nome/UID local da fonte não precisa ser igual ao desta
máquina. O job e target devem continuar correspondendo aos seletores acima.

Na stack, o arquivo portátil é convertido apenas na cópia de runtime dentro de
`.local`, resolvendo a fonte de dados para `av02-prometheus` e preservando o JSON
original exportado. `browser.mjs` usa o Edge real com um perfil isolado em D: para
inspecionar a interface, exportar, verificar refresh e capturar PNG, sem gerar imagens
simuladas ou acessar o perfil pessoal do navegador.

```powershell
# Requer Node.js 22+ no PATH da sessão.
node .\observability\browser.mjs screenshot

# Reproduzir a exportação oficial pela interface:
node .\observability\browser.mjs export

# Importar uma cópia temporária e conferir consultas/fonte:
.\observability\import-check.ps1
# Depois da conferência, remover somente a cópia criada pelo teste:
.\observability\import-check.ps1 cleanup
```

A captura real de entrega fica em `evidence/dashboard-grafana.png`. Também é
possível capturar manualmente o dashboard aberto, incluindo título e três painéis,
sem exibir credenciais. O relatório da Etapa 6 lista as verificações efetivamente
executadas e os artefatos finais.

`node .\observability\browser.mjs refresh` verifica a atualização automática
sem clicar em Refresh: gera uma tentativa fictícia com usuário inexistente,
espera o indicador de inválidos aumentar em um e captura a tela resultante.
Isso incrementa o Counter de falhas do banco de demonstração; não cria usuário
nem altera bloqueios. `evidence/browser-refresh.json` registra a mudança e as
requisições automáticas observadas pelo navegador.

## Regressão da aplicação

```powershell
.\dev.ps1 test
.\dev.ps1 persistence-write
.\dev.ps1 persistence-read
Remove-Item Env:AV02_RESTART_TEST_PASSWORD
```

Esses testes continuam usando bancos e portas isolados; não dependem de Prometheus
ou Grafana. A integração não altera o código Java, as roles nem a exposição do Actuator.
