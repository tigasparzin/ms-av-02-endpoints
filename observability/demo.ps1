#requires -Version 7.0
. (Join-Path $PSScriptRoot 'common.ps1')
$evidence = [System.Collections.Generic.List[object]]::new()
$demoPassword = $credentials.demo.GetNetworkCredential().Password
function App([string]$method,[string]$path,$body,$auth) {
    $parameters = @{ Uri=('http://127.0.0.1:8080'+$path); Method=$method; SkipHttpErrorCheck=$true; TimeoutSec=10 }
    if ($body) { $parameters.Body=($body | ConvertTo-Json -Compress); $parameters.ContentType='application/json' }
    if ($auth) { $parameters.Headers=Basic-Header $auth }
    return (Invoke-WebRequest @parameters).StatusCode
}
function Expect([int]$actual,[int]$expected) {
    if ($actual -ne $expected) { throw "Expected HTTP $expected, received $actual. No credentials are included in this error." }
}
function Record([string]$step,[double]$success,[double]$failure,[double]$locked) {
    $state = Read-Actuator
    $expected = [pscustomobject]@{success=$success;failure=$failure;locked=$locked}
    if (-not (Same-State $state $expected)) { throw "Actuator value mismatch after $step" }
    $dashboard = Wait-Integration $expected
    $evidence.Add([pscustomobject]@{step=$step;timeUtc=[DateTime]::UtcNow.ToString('O');actuator=$state;prometheus=(Read-Prometheus);grafana=$dashboard})
    Write-Output "$step : success=$success failure=$failure locked=$locked (Actuator = Prometheus = Grafana)"
}
foreach ($name in 'demo-login','demo-bloqueado') {
    $code = App POST '/' @{username=$name;senha=$demoPassword} $credentials.maintenance
    if ($code -notin 201,409) { throw "Demo account preparation returned HTTP $code" }
    Expect (App PUT "/unlock/$name" $null $credentials.admin) 204
}
$baseline = Read-Actuator
$s=$baseline.success; $f=$baseline.failure; $b=$baseline.locked
Record 'baseline' $s $f $b
Expect (App POST '/login' @{username='demo-login';senha=$demoPassword} $null) 200
Record 'valid-login' (++$s) $f $b
Expect (App POST '/login' @{username='demo-login';senha='invalid-demo-password'} $null) 401
Record 'first-invalid-login' $s (++$f) $b
Expect (App POST '/login' @{username='demo-login';senha='invalid-demo-password'} $null) 401
Record 'second-invalid-login-locks' $s (++$f) ($b+1)
Expect (App POST '/login' @{username='demo-login';senha=$demoPassword} $null) 401
Record 'blocked-login-counter-only' $s (++$f) ($b+1)
Expect (App PUT '/unlock/demo-login' $null $credentials.admin) 204
Record 'administrative-unlock' $s $f $b
Expect (App GET '/' $null $credentials.maintenance) 200
$wrongBasic = [pscredential]::new('manutencao',(ConvertTo-SecureString 'invalid-demo-password' -AsPlainText -Force))
Expect (App GET '/' $null $wrongBasic) 401
Record 'http-basic-does-not-count' $s $f $b
Expect (App POST '/login' @{username='demo-login';senha=$demoPassword} $null) 200
Expect (App POST '/login' @{username='demo-bloqueado';senha='invalid-demo-password'} $null) 401
Expect (App POST '/login' @{username='demo-bloqueado';senha='invalid-demo-password'} $null) 401
Record 'representative-final-state' (++$s) ($f+2) ($b+1)
$evidenceDir = Join-Path $PSScriptRoot 'evidence'
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$evidence.ToArray() | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $evidenceDir 'integration.json') -Encoding utf8
