param(
    [ValidateSet('test', 'package', 'run', 'persistence-write', 'persistence-read')]
    [string]$Task = 'test'
)
$ErrorActionPreference = 'Stop'
if ([IO.Path]::GetPathRoot($PSScriptRoot) -ne 'D:\') {
    throw 'AV02 must run on drive D:.'
}
$av02Local = Join-Path $PSScriptRoot '.local'
$av02Temp = Join-Path $av02Local 'tmp'
New-Item -ItemType Directory -Force -Path $av02Temp | Out-Null
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:TEMP = $av02Temp
$env:TMP = $av02Temp
$env:MAVEN_OPTS = '-Djava.io.tmpdir="' + $av02Temp + '"'
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir="' + $av02Temp + '"'
$av02Maven = 'D:\!Theo\dev\maven\apache-maven-3.9.9\bin\mvn.cmd'
$av02Repo = Join-Path $av02Local 'm2\repository'
$av02Args = @("-Dmaven.repo.local=$av02Repo", '--no-transfer-progress')
if ($Task -eq 'persistence-write') {
    # Shared only through the current terminal environment, never written to disk.
    $env:AV02_RESTART_TEST_PASSWORD = [Guid]::NewGuid().ToString()
}
if ($Task -eq 'persistence-read' -and [string]::IsNullOrWhiteSpace($env:AV02_RESTART_TEST_PASSWORD)) {
    throw 'Execute persistence-write before persistence-read in the same terminal.'
}
switch ($Task) {
    'test' { $av02Args += 'test' }
    'package' { $av02Args += 'package' }
    'run' { $av02Args += 'spring-boot:run' }
    'persistence-write' { $av02Args += @('-Dtest=PersistenceRestartTest', '-Dav02.persistence.phase=write', 'test') }
    'persistence-read' { $av02Args += @('-Dtest=PersistenceRestartTest', '-Dav02.persistence.phase=read', 'test') }
}
Push-Location $PSScriptRoot
try {
    & $av02Maven @av02Args
    if ($LASTEXITCODE -ne 0) { throw "Maven failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
