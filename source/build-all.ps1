# Builds Nekoro Shop for every Minecraft version and loader, then copies the jars to ..\Gotowe\<version> (next to the source folder).
# Usage:  & .\build-all.ps1                                       (all projects)
#         & .\build-all.ps1 fabric-1.21.1                         (selected ones)
# The first NeoForge build takes about 10 minutes (Minecraft decompilation), later ones under a minute.
param([string[]]$Projects = @('neoforge-1.21.1', 'fabric-1.21.1'))

# Not 'Stop': Gradle writes plain info lines to stderr, and PowerShell 5.1 would treat them as errors
# when output is redirected. Success is judged by the gradlew exit code instead.
$ErrorActionPreference = 'Continue'
$root = $PSScriptRoot
# Gradle needs JDK 21; a local copy is used when present, otherwise JAVA_HOME as set
$jdk = 'C:\Users\AlolanXardas\Narzedzia\jdk-21'
if (Test-Path $jdk) { $env:JAVA_HOME = $jdk }
# each loader has its own release number (Fabric started later), read from the project's gradle.properties
$gotowe = Join-Path (Split-Path $root -Parent) 'Gotowe'

$results = @()
foreach ($p in $Projects) {
    $dir = Join-Path $root $p
    Write-Host "=== $p" -ForegroundColor Cyan
    [Environment]::CurrentDirectory = $dir
    Push-Location $dir
    & "$dir\gradlew.bat" build --console=plain
    $ok = $LASTEXITCODE -eq 0
    Pop-Location
    if ($ok) {
        $version = (Select-String -Path "$dir\gradle.properties" -Pattern '^mod_version=(.+)$').Matches[0].Groups[1].Value
        $out = Join-Path $gotowe $version
        New-Item -ItemType Directory -Force $out | Out-Null
        Get-ChildItem "$dir\build\libs" -Filter "Nekoro-S-$version-*.jar" | Copy-Item -Destination $out -Force
        $results += "OK      $p -> Gotowe\$version"
    } else {
        $results += "FAILED  $p"
    }
}
Write-Host ''
$results | ForEach-Object { Write-Host $_ }
