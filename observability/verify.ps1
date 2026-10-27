param([string]$EvidenceName = 'verification')
. (Join-Path $PSScriptRoot 'common.ps1')
$actual = Read-Actuator
$grafana = Wait-Integration $actual
$target = @((Invoke-RestMethod 'http://127.0.0.1:9090/api/v1/targets').data.activeTargets | Where-Object { $_.labels.job -eq 'av02' })
if ($target.Count -ne 1 -or $target[0].health -ne 'up' -or $target[0].lastError) { throw 'Expected one healthy AV02 target.' }
$health = Invoke-RestMethod 'http://127.0.0.1:3000/api/datasources/uid/av02-prometheus/health' -Headers (Basic-Header $credentials.grafana)
if ($health.status -ne 'OK') { throw 'Grafana datasource health failed.' }
$dashboard = (Invoke-RestMethod 'http://127.0.0.1:3000/api/dashboards/uid/av02-seguranca-observabilidade' -Headers (Basic-Header $credentials.grafana)).dashboard
if ($dashboard.title -ne 'AV-02 — Segurança e Observabilidade' -or $dashboard.panels.Count -ne 3 -or $dashboard.refresh -ne '5s') { throw 'Unexpected dashboard layout or refresh.' }
foreach ($panel in $dashboard.panels) {
    if ($panel.type -ne 'stat' -or -not $panel.targets[0].instant -or $panel.targets[0].range -or $panel.targets[0].expr -match 'rate\(|or vector|sum\(') { throw 'Unexpected panel semantics.' }
}
$build = (Invoke-RestMethod 'http://127.0.0.1:9090/api/v1/status/buildinfo').data
$grafanaVersion = (Invoke-RestMethod 'http://127.0.0.1:3000/api/health').version
$hash = (Get-FileHash (Join-Path $av02Root 'data\av02.mv.db') -Algorithm SHA256).Hash
$baselineFile = Join-Path $obsHome 'normal-db.sha256'
if (Test-Path $baselineFile) {
    if ($hash -ne (Get-Content $baselineFile -Raw).Trim()) { throw 'Normal database checksum changed.' }
}
$evidence = [pscustomobject]@{
    timeUtc=[DateTime]::UtcNow.ToString('O'); prometheusVersion=$build.version; grafanaVersion=$grafanaVersion;
    target='UP'; scrapeUrl=$target[0].scrapeUrl; lastError=$target[0].lastError; scrapeInterval=$target[0].scrapeInterval;
    actuator=$actual; prometheus=(Read-Prometheus); grafana=$grafana;
    datasourceHealth=$health.status; dashboardTitle=$dashboard.title; refresh=$dashboard.refresh;
    panels=@($dashboard.panels | ForEach-Object { @{ title=$_.title; type=$_.type; query=$_.targets[0].expr; instant=$_.targets[0].instant } });
    normalDatabaseSha256=$hash
}
New-Item -ItemType Directory -Force -Path (Join-Path $PSScriptRoot 'evidence') | Out-Null
$evidence | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $PSScriptRoot "evidence\$EvidenceName.json") -Encoding utf8
$evidence | ConvertTo-Json -Depth 10
