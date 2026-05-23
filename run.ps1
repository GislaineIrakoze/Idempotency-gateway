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
if (-not $sourceFiles) {
    throw "No Java source files found."
}

New-Item -ItemType Directory -Force -Path "out" | Out-Null
& $javac -d out $sourceFiles
& $java -cp out com.igirepay.gateway.Application
