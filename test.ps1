$ErrorActionPreference = "Stop"

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $javaHome = Get-ChildItem -Path "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

$javac = if ($javaHome) { Join-Path $javaHome "bin\javac.exe" } else { "javac" }
$java = if ($javaHome) { Join-Path $javaHome "bin\java.exe" } else { "java" }

if (-not (Test-Path $javac)) {
    throw "Could not find javac. Install JDK 17+, or set JAVA_HOME to your JDK folder."
}

if (-not (Test-Path $java)) {
    throw "Could not find java. Install JDK 17+, or set JAVA_HOME to your JDK folder."
}

$sourceFiles = Get-ChildItem -Path "src/main/java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$testFiles = Get-ChildItem -Path "src/test/java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

New-Item -ItemType Directory -Force -Path "out" | Out-Null
& $javac -d out $sourceFiles $testFiles

$port = if ($env:TEST_PORT) { $env:TEST_PORT } else { "8090" }
$server = Start-Process -FilePath $java `
    -ArgumentList "-DPORT=$port", "-cp", "out", "com.igirepay.gateway.Application" `
    -WorkingDirectory (Get-Location) `
    -WindowStyle Hidden `
    -PassThru

try {
    Start-Sleep -Seconds 2
    & $java "-DBASE_URL=http://127.0.0.1:$port" -cp out com.igirepay.gateway.AcceptanceTest
} finally {
    if ($server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
    }
}

