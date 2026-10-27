$ErrorActionPreference = 'Stop'
$av02Root = Split-Path $PSScriptRoot -Parent
if ([IO.Path]::GetPathRoot($av02Root) -ne 'D:\') { throw 'Use AV02 on D:.' }
$obsHome = Join-Path $av02Root '.local\observability'
$downloads = Join-Path $obsHome 'downloads'
$toolsDir = Join-Path $obsHome 'tools'
New-Item -ItemType Directory -Force -Path $downloads,$toolsDir,(Join-Path $obsHome 'tmp') | Out-Null
$env:TEMP = Join-Path $obsHome 'tmp'
$env:TMP = $env:TEMP
$assets = @(
    @{ Name='prometheus-3.13.5.windows-amd64.zip'; Url='https://github.com/prometheus/prometheus/releases/download/v3.13.5/prometheus-3.13.5.windows-amd64.zip'; Sha256='846398b50b90e02f69960342190a1d01bf9a335b3aeee69bfd6ead41d3a314c5'; Executable='prometheus.exe' },
    @{ Name='grafana_13.2.4_38252365904_windows_amd64.tar.gz'; Url='https://dl.grafana.com/grafana/release/13.2.4/grafana_13.2.4_38252365904_windows_amd64.tar.gz'; Sha256='3e005ff50fe3bb6b8cb428ef127636fac4ee676bf9b6d819c6a2297d21f4bcbd'; Executable='grafana.exe' }
)
foreach ($asset in $assets) {
    $archive = Join-Path $downloads $asset.Name
    if (-not (Test-Path -LiteralPath $archive)) {
        Write-Output "Downloading $($asset.Name) into AV02 on D:"
        Invoke-WebRequest -Uri $asset.Url -OutFile $archive -TimeoutSec 600
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $asset.Sha256) {
        throw "SHA256 mismatch: $($asset.Name). Preserve file for inspection; no extraction performed."
    }
    $found = Get-ChildItem -LiteralPath $toolsDir -Recurse -File -Filter $asset.Executable | Select-Object -First 1
    if (-not $found) {
        & tar.exe -xf $archive -C $toolsDir
        if ($LASTEXITCODE -ne 0) { throw "Extraction failed: $($asset.Name)" }
    }
    Write-Output "Verified $($asset.Name)"
}
$prometheus = (Get-ChildItem -LiteralPath $toolsDir -Recurse -File -Filter prometheus.exe | Select-Object -First 1).FullName
$grafana = (Get-ChildItem -LiteralPath $toolsDir -Recurse -File -Filter grafana.exe | Select-Object -First 1).FullName
if (-not $prometheus -or -not $grafana) { throw 'Portable executables not found.' }
@{ prometheus=$prometheus; grafana=$grafana } | ConvertTo-Json | Set-Content (Join-Path $obsHome 'tools.json') -Encoding utf8
& $prometheus --version
& $grafana --version
