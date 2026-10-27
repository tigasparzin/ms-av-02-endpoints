# Local integration helpers. Never print credentials, HTTP bodies containing users or hashes.
$ErrorActionPreference = 'Stop'
$av02Root = Split-Path $PSScriptRoot -Parent
$obsHome = Join-Path $av02Root '.local\observability'
$credentials = Import-Clixml -LiteralPath (Join-Path $obsHome 'credentials.clixml')
$applicationEntry = @(Get-Content (Join-Path $obsHome 'processes.json') -Raw | ConvertFrom-Json) | Where-Object name -eq 'application'
$demoProcess = Get-Process -Id $applicationEntry.pid -ErrorAction Stop
if ($demoProcess.StartTime.ToUniversalTime() -ne ([datetime]$applicationEntry.started).ToUniversalTime()) { throw 'Demo application process identity changed.' }
$demoCommand = (Get-CimInstance Win32_Process -Filter "ProcessId = $($applicationEntry.pid)").CommandLine
if (-not $demoCommand.Contains('--spring.datasource.url=jdbc:h2:file:./.local/observability/app/av02-demo;DB_CLOSE_ON_EXIT=FALSE')) {
    throw 'Refusing to run integration operations outside the isolated demo database.'
}
function Basic-Header([pscredential]$credential) {
    return @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential.UserName + ':' + $credential.GetNetworkCredential().Password)) }
}
function Read-Actuator {
    $body = (Invoke-WebRequest 'http://127.0.0.1:9099/actuator/prometheus' -TimeoutSec 5).Content
    $values = [ordered]@{}
    foreach ($pair in @(@('success','av02_login_success_total'),@('failure','av02_login_failure_total'),@('locked','av02_users_locked'))) {
        $match = [regex]::Match($body, '(?m)^' + $pair[1] + ' ([0-9.E+\-]+)\r?$')
        if (-not $match.Success) { throw "Metric missing: $($pair[1])" }
        $values[$pair[0]] = [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
    }
    return [pscustomobject]$values
}
function Read-Prometheus {
    $state = [ordered]@{}
    foreach ($pair in @(@('success','av02_login_success_total'),@('failure','av02_login_failure_total'),@('locked','av02_users_locked'))) {
        $query = $pair[1] + '{job="av02",instance="127.0.0.1:9099"}'
        $response = Invoke-RestMethod ('http://127.0.0.1:9090/api/v1/query?query=' + [Uri]::EscapeDataString($query)) -TimeoutSec 5
        if ($response.status -ne 'success' -or @($response.data.result).Count -ne 1) { throw "Missing or duplicate Prometheus series: $($pair[1])" }
        $state[$pair[0]] = [double]::Parse($response.data.result[0].value[1], [Globalization.CultureInfo]::InvariantCulture)
    }
    return [pscustomobject]$state
}
function Read-Grafana {
    $queries = @()
    $index = 0
    foreach ($metric in 'av02_login_success_total','av02_login_failure_total','av02_users_locked') {
        $queries += @{ refId=([string][char](65+$index)); datasource=@{type='prometheus';uid='av02-prometheus'}; expr=($metric + '{job="av02",instance="127.0.0.1:9099"}'); instant=$true; range=$false; format='time_series'; intervalMs=5000; maxDataPoints=1 }
        $index++
    }
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $requestBody = @{ queries=$queries; from=([string]($now-900000)); to=([string]$now) } | ConvertTo-Json -Depth 10
    $result = Invoke-RestMethod 'http://127.0.0.1:3000/api/ds/query' -Method Post -Headers (Basic-Header $credentials.grafana) -ContentType application/json -Body $requestBody -TimeoutSec 10
    $state = [ordered]@{}
    $index = 0
    foreach ($name in 'success','failure','locked') {
        $ref = [string][char](65+$index)
        $frames = @($result.results.$ref.frames)
        if ($result.results.$ref.error -or $frames.Count -ne 1) { throw "Grafana query failed or returned duplicate/no frames: $name" }
        $fields = @($frames[0].schema.fields)
        $numberIndex = -1
        for ($i=0; $i -lt $fields.Count; $i++) { if ($fields[$i].type -eq 'number') { $numberIndex=$i } }
        if ($numberIndex -lt 0) { throw "Grafana has no numeric field: $name" }
        $numbers = @($frames[0].data.values[$numberIndex])
        if ($numbers.Count -ne 1 -or $null -eq $numbers[0]) { throw "Grafana has no single instant value: $name" }
        $state[$name] = [double]$numbers[0]
        $index++
    }
    return [pscustomobject]$state
}
function Same-State($left,$right) {
    return $left.success -eq $right.success -and $left.failure -eq $right.failure -and $left.locked -eq $right.locked
}
function Wait-Integration($expected) {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        $targets = Invoke-RestMethod 'http://127.0.0.1:9090/api/v1/targets' -TimeoutSec 5
        $target = @($targets.data.activeTargets | Where-Object { $_.labels.job -eq 'av02' })
        if ($target.Count -eq 1 -and $target[0].health -eq 'up') {
            $prom = Read-Prometheus
            if (Same-State $prom $expected) {
                $grafana = Read-Grafana
                if (Same-State $grafana $expected) { return $grafana }
            }
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Actuator, Prometheus and Grafana did not converge. No artificial zero fallback is used.'
}
