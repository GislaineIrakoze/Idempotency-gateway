$ErrorActionPreference = "Stop"

$baseUrl = if ($env:BASE_URL) { $env:BASE_URL } else { "http://localhost:8080" }
$key = "smoke-test-$(Get-Date -Format yyyyMMddHHmmss)"
$body = '{"amount": 100, "currency": "RWF"}'
$changedBody = '{"amount": 500, "currency": "RWF"}'

function Send-Payment($requestBody) {
    Invoke-WebRequest `
        -UseBasicParsing `
        -Uri "$baseUrl/process-payment" `
        -Method POST `
        -Headers @{ "Idempotency-Key" = $key } `
        -ContentType "application/json" `
        -Body $requestBody
}

Write-Host "First request should take about 2 seconds..."
$firstDuration = Measure-Command { $script:first = Send-Payment $body }
Write-Host "Status: $($first.StatusCode), Body: $($first.Content), Duration: $([math]::Round($firstDuration.TotalSeconds, 2))s"

Write-Host "Duplicate request should replay immediately..."
$secondDuration = Measure-Command { $script:second = Send-Payment $body }
Write-Host "Status: $($second.StatusCode), X-Cache-Hit: $($second.Headers['X-Cache-Hit']), Body: $($second.Content), Duration: $([math]::Round($secondDuration.TotalSeconds, 2))s"

Write-Host "Different payload with same key should return 409..."
try {
    $conflict = Send-Payment $changedBody
    Write-Host "Unexpected status: $($conflict.StatusCode), Body: $($conflict.Content)"
} catch {
    $statusCode = [int]$_.Exception.Response.StatusCode
    $content = $_.ErrorDetails.Message
    if (-not $content) {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = [System.IO.StreamReader]::new($stream)
        $content = $reader.ReadToEnd()
    }
    Write-Host "Status: $statusCode, Body: $content"
}
