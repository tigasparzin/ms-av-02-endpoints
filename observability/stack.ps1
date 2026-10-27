param([ValidateSet('start','stop','status')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
$av02Root = Split-Path $PSScriptRoot -Parent
$obsHome = Join-Path $av02Root '.local\observability'
$pidFile = Join-Path $obsHome 'processes.json'
$credentialFile = Join-Path $obsHome 'credentials.clixml'

function Read-Processes {
    if (Test-Path -LiteralPath $pidFile) { return @(Get-Content $pidFile -Raw | ConvertFrom-Json) }
    return @()
}

if ($Action -eq 'stop') {
    foreach ($entry in (Read-Processes)) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if ($process) {
            if ($process.StartTime.ToUniversalTime() -ne ([datetime]$entry.started).ToUniversalTime()) { throw "PID $($entry.pid) was reused; refusing to stop it." }
            Stop-Process -Id $process.Id
            $process.WaitForExit()
            Write-Output "Stopped $($entry.name) (owned PID $($entry.pid)). Data preserved."
        }
    }
    return
}
if ($Action -eq 'status') {
    foreach ($entry in (Read-Processes)) {
        $process = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        [pscustomobject]@{ Service=$entry.name; PID=$entry.pid; Running=([bool]$process -and $process.StartTime.ToUniversalTime() -eq ([datetime]$entry.started).ToUniversalTime()) }
    }
    return
}

if ([IO.Path]::GetPathRoot($av02Root) -ne 'D:\') { throw 'Use AV02 on D:.' }
foreach ($entry in (Read-Processes)) {
    if (Get-Process -Id $entry.pid -ErrorAction SilentlyContinue) { throw 'A recorded process is running. Check status/stop before starting again.' }
}
foreach ($port in 8080,9099,9090,3000) {
    $listener = [Net.Sockets.TcpClient]::new()
    try { $listener.Connect('127.0.0.1', $port); throw "Port $port is in use; no existing service will be changed." }
    catch [Net.Sockets.SocketException] { }
    finally { $listener.Dispose() }
}
if (-not (Test-Path (Join-Path $obsHome 'tools.json'))) { throw 'Run observability/install.ps1 first.' }
$tools = Get-Content (Join-Path $obsHome 'tools.json') -Raw | ConvertFrom-Json
$jar = Join-Path $av02Root 'target\av02-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path $jar)) { throw 'Build the application with dev.ps1 package first.' }
New-Item -ItemType Directory -Force -Path (Join-Path $obsHome 'tmp'),(Join-Path $obsHome 'app'),(Join-Path $obsHome 'logs'),(Join-Path $obsHome 'prometheus-data'),(Join-Path $obsHome 'grafana-data'),(Join-Path $obsHome 'grafana-plugins'),(Join-Path $obsHome 'provisioning\datasources'),(Join-Path $obsHome 'provisioning\dashboards'),(Join-Path $obsHome 'dashboards') | Out-Null
$env:TEMP = Join-Path $obsHome 'tmp'
$env:TMP = $env:TEMP
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="' + $env:TEMP + '"'
if (-not (Test-Path (Join-Path $obsHome 'normal-db.sha256')) -and (Test-Path (Join-Path $av02Root 'data\av02.mv.db'))) {
    (Get-FileHash (Join-Path $av02Root 'data\av02.mv.db') -Algorithm SHA256).Hash | Set-Content (Join-Path $obsHome 'normal-db.sha256') -Encoding ascii
}
if (-not (Test-Path $credentialFile)) {
    $credentials = [pscustomobject]@{
        admin = [pscredential]::new('admin', (ConvertTo-SecureString ([Guid]::NewGuid().ToString()) -AsPlainText -Force))
        maintenance = [pscredential]::new('manutencao', (ConvertTo-SecureString ([Guid]::NewGuid().ToString()) -AsPlainText -Force))
        grafana = [pscredential]::new('av02-local-admin', (ConvertTo-SecureString ([Guid]::NewGuid().ToString()) -AsPlainText -Force))
        demo = [pscredential]::new('demo', (ConvertTo-SecureString ([Guid]::NewGuid().ToString()) -AsPlainText -Force))
    }
    $credentials | Export-Clixml -LiteralPath $credentialFile
}
$credentials = Import-Clixml -LiteralPath $credentialFile
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'datasource.yml') -Destination (Join-Path $obsHome 'provisioning\datasources\av02.yml')
$dashboardText = Get-Content (Join-Path $PSScriptRoot 'dashboard\av02-dashboard.json') -Raw
$dashboard = $dashboardText | ConvertFrom-Json
foreach ($input in @($dashboard.__inputs)) {
    if ($input -and $input.type -eq 'datasource') { $dashboardText = $dashboardText.Replace(('${' + $input.name + '}'), 'av02-prometheus') }
}
$dashboard = $dashboardText | ConvertFrom-Json
$dashboard.PSObject.Properties.Remove('__inputs')
$dashboard | Add-Member -NotePropertyName id -NotePropertyValue $null -Force
$dashboard | Add-Member -NotePropertyName uid -NotePropertyValue 'av02-seguranca-observabilidade' -Force
$dashboard | Add-Member -NotePropertyName version -NotePropertyValue 1 -Force
$dashboard | ConvertTo-Json -Depth 100 | Set-Content (Join-Path $obsHome 'dashboards\av02-dashboard.json') -Encoding utf8
$dashboardPath = (Join-Path $obsHome 'dashboards').Replace('\','/')
@"
apiVersion: 1
providers:
  - name: AV02
    folder: AV02
    type: file
    disableDeletion: true
    allowUiUpdates: true
    updateIntervalSeconds: 10
    options:
      path: '$dashboardPath'
"@ | Set-Content (Join-Path $obsHome 'provisioning\dashboards\av02.yml') -Encoding utf8
$started = [System.Collections.Generic.List[object]]::new()
function Start-OwnedProcess([string]$name, [string]$executable, [string[]]$arguments, [string]$workingDirectory) {
    $quoted = $arguments | ForEach-Object { '"' + $_ + '"' }
    $process = Start-Process -FilePath $executable -ArgumentList $quoted -WorkingDirectory $workingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $obsHome "logs\$name.stdout.log") -RedirectStandardError (Join-Path $obsHome "logs\$name.stderr.log")
    $started.Add([pscustomobject]@{ name=$name; pid=$process.Id; started=$process.StartTime.ToUniversalTime().ToString('O') })
    @($started.ToArray()) | ConvertTo-Json -Depth 5 | Set-Content $pidFile -Encoding utf8
    return $process
}
function Wait-Http([string]$url, [Diagnostics.Process]$process) {
    # First Grafana boot performs database migrations and scans bundled plugins.
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    do {
        if ($process.HasExited) { throw "Service exited before readiness: $url. Inspect local logs." }
        try { if ((Invoke-WebRequest -Uri $url -TimeoutSec 2).StatusCode -eq 200) { return } } catch { }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Readiness timeout: $url"
}
try {
    $env:AV02_ADMIN_PASSWORD = $credentials.admin.GetNetworkCredential().Password
    $env:AV02_MANUTENCAO_PASSWORD = $credentials.maintenance.GetNetworkCredential().Password
    $app = Start-OwnedProcess 'application' 'C:\Program Files\Java\jdk-21\bin\java.exe' @('-jar', $jar, '--spring.datasource.url=jdbc:h2:file:./.local/observability/app/av02-demo;DB_CLOSE_ON_EXIT=FALSE') $av02Root
    Wait-Http 'http://127.0.0.1:9099/actuator/prometheus' $app
    Remove-Item Env:AV02_ADMIN_PASSWORD,Env:AV02_MANUTENCAO_PASSWORD -ErrorAction SilentlyContinue
    $promHome = Split-Path $tools.prometheus -Parent
    $promtool = Join-Path $promHome 'promtool.exe'
    & $promtool check config (Join-Path $PSScriptRoot 'prometheus.yml')
    if ($LASTEXITCODE -ne 0) { throw 'Invalid Prometheus configuration.' }
    $prom = Start-OwnedProcess 'prometheus' $tools.prometheus @('--config.file', (Join-Path $PSScriptRoot 'prometheus.yml'), '--storage.tsdb.path', (Join-Path $obsHome 'prometheus-data'), '--storage.tsdb.retention.time=7d', '--web.listen-address=127.0.0.1:9090') $promHome
    Wait-Http 'http://127.0.0.1:9090/-/ready' $prom
    $env:GF_SECURITY_ADMIN_USER = $credentials.grafana.UserName
    $env:GF_SECURITY_ADMIN_PASSWORD = $credentials.grafana.GetNetworkCredential().Password
    $grafanaHome = Split-Path (Split-Path $tools.grafana -Parent) -Parent
    $grafana = Start-OwnedProcess 'grafana' $tools.grafana @('server', '--homepath', $grafanaHome, '--config', (Join-Path $PSScriptRoot 'grafana.ini'), "cfg:default.paths.data=$(Join-Path $obsHome 'grafana-data')", "cfg:default.paths.logs=$(Join-Path $obsHome 'logs')", "cfg:default.paths.plugins=$(Join-Path $obsHome 'grafana-plugins')", "cfg:default.paths.provisioning=$(Join-Path $obsHome 'provisioning')") $grafanaHome
    Wait-Http 'http://127.0.0.1:3000/api/health' $grafana
    Write-Output 'Application: http://127.0.0.1:8080 (isolated demo database)'
    Write-Output 'Actuator: http://127.0.0.1:9099/actuator/prometheus'
    Write-Output 'Prometheus: http://127.0.0.1:9090/targets'
    Write-Output 'Grafana: http://127.0.0.1:3000/d/av02-seguranca-observabilidade'
} finally {
    Remove-Item Env:AV02_ADMIN_PASSWORD,Env:AV02_MANUTENCAO_PASSWORD,Env:GF_SECURITY_ADMIN_USER,Env:GF_SECURITY_ADMIN_PASSWORD -ErrorAction SilentlyContinue
}
