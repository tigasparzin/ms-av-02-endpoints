param([ValidateSet('import','cleanup')][string]$Action = 'import')
. (Join-Path $PSScriptRoot 'common.ps1')
$proofFile = Join-Path $PSScriptRoot 'evidence\import-verification.json'
$headers = Basic-Header $credentials.grafana
if ($Action -eq 'cleanup') {
    $proof = Get-Content $proofFile -Raw | ConvertFrom-Json
    if ($proof.uid -notlike 'av02-import-*') { throw 'Refusing to delete an unrelated dashboard.' }
    $current = (Invoke-RestMethod ("http://127.0.0.1:3000/api/dashboards/uid/"+$proof.uid) -Headers $headers).dashboard
    if ($current.title -ne $proof.title) { throw 'Imported dashboard identity changed; refusing cleanup.' }
    Invoke-RestMethod ("http://127.0.0.1:3000/api/dashboards/uid/"+$proof.uid) -Method Delete -Headers $headers | Out-Null
    $proof | Add-Member -NotePropertyName temporaryCopyRemoved -NotePropertyValue $true -Force
    $proof | ConvertTo-Json -Depth 10 | Set-Content $proofFile -Encoding utf8
    Write-Output 'Only the temporary imported copy created by this check was removed.'
    return
}
$model = Get-Content (Join-Path $PSScriptRoot 'dashboard\av02-dashboard.json') -Raw | ConvertFrom-Json
$expectedTitle = $model.title + ' — importação verificada'
$uid = 'av02-import-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$model | Add-Member -NotePropertyName id -NotePropertyValue $null -Force
$model | Add-Member -NotePropertyName uid -NotePropertyValue $uid -Force
$model | Add-Member -NotePropertyName title -NotePropertyValue $expectedTitle -Force
$inputs = @($model.__inputs | ForEach-Object { @{name=$_.name;type='datasource';pluginId='prometheus';value='av02-prometheus'} })
$body = @{dashboard=$model;overwrite=$false;inputs=$inputs} | ConvertTo-Json -Depth 100
$result = Invoke-RestMethod 'http://127.0.0.1:3000/api/dashboards/import' -Method Post -Headers $headers -ContentType application/json -Body $body
$imported = (Invoke-RestMethod ("http://127.0.0.1:3000/api/dashboards/uid/"+$uid) -Headers $headers).dashboard
if ($imported.panels.Count -ne 3 -or $imported.title -ne $expectedTitle) { throw 'Imported dashboard structure is incorrect.' }
for ($i=0;$i -lt 3;$i++) {
    if ($imported.panels[$i].targets[0].expr -ne $model.panels[$i].targets[0].expr -or $imported.panels[$i].datasource.uid -ne 'av02-prometheus') { throw 'Imported queries/datasource differ from export.' }
}
$state = Wait-Integration (Read-Actuator)
$proof = [pscustomobject]@{timeUtc=[DateTime]::UtcNow.ToString('O');uid=$uid;title=$expectedTitle;url=('http://127.0.0.1:3000/d/'+$uid);panels=@($imported.panels | ForEach-Object { @{title=$_.title;query=$_.targets[0].expr;datasourceUid=$_.datasource.uid} });values=$state;temporaryCopyRemoved=$false}
$proof | ConvertTo-Json -Depth 10 | Set-Content $proofFile -Encoding utf8
$proof | ConvertTo-Json -Depth 10
