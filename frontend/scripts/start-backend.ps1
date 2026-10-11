param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$frontendDirectory = Split-Path -Parent $PSScriptRoot
$projectDirectory = Split-Path -Parent $frontendDirectory
$localConfig = Join-Path $frontendDirectory '.backend.local.properties'

try {
    if (-not (Test-Path -LiteralPath $localConfig)) {
        throw 'Copy frontend/.backend.example.properties to .backend.local.properties and fill in the MySQL credentials first.'
    }

    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        $javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
        $javaProbe = [System.Diagnostics.Process]::new()
        $javaProbe.StartInfo.FileName = $javaExecutable
        $javaProbe.StartInfo.Arguments = '-XshowSettings:properties -version'
        $javaProbe.StartInfo.UseShellExecute = $false
        $javaProbe.StartInfo.CreateNoWindow = $true
        $javaProbe.StartInfo.RedirectStandardError = $true
        [void]$javaProbe.Start()
        $javaSettings = $javaProbe.StandardError.ReadToEnd() -split "`n"
        $javaProbe.WaitForExit()
        $javaProbe.Dispose()
        $javaHomeSetting = $javaSettings | Where-Object { $_ -match '^\s*java.home\s*=' } | Select-Object -First 1
        if ($javaHomeSetting) { $env:JAVA_HOME = ($javaHomeSetting -split '=', 2)[1].Trim() }
    }
    $javaVersionLines = @(& $javaExecutable --version)
    $javaVersion = $javaVersionLines[0]
    if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '^(?:openjdk|java)\s+(\d+)' -or [int]$Matches[1] -lt 21) {
        throw 'This backend requires JDK 21 or later. Set JAVA_HOME to that JDK (a Java 17 PATH entry is not sufficient).'
    }

    Push-Location $projectDirectory
    try {
        if (-not $SkipBuild) {
            $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
            $cachedMaven = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Filter 'mvn.cmd' -File -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
            $mavenExecutable = if ($mavenCommand) { $mavenCommand.Source } elseif ($cachedMaven) { $cachedMaven.FullName } else { Join-Path $projectDirectory 'mvnw.cmd' }
            $env:MAVEN_OPTS = "$env:MAVEN_OPTS -Dfile.encoding=UTF-8".Trim()
            & $mavenExecutable '-DskipTests' 'package'
            if ($LASTEXITCODE -ne 0) { throw 'Backend build failed. See Maven output above.' }
        }
        $backendJar = Join-Path $projectDirectory 'target/water-0.0.1-SNAPSHOT.jar'
        if (-not (Test-Path -LiteralPath $backendJar)) { throw 'Backend JAR is missing. Run this script without -SkipBuild.' }

        Write-Host "Java: $javaVersion"
        Write-Host 'Backend: http://127.0.0.1:8080 (unless overridden in local configuration)'
        Write-Host 'Using frontend/.backend.local.properties. Keep this window open; Ctrl+C stops the backend.'
        $configUri = ([System.Uri]$localConfig).AbsoluteUri
        & $javaExecutable '-Dfile.encoding=UTF-8' '-jar' $backendJar "--spring.config.additional-location=$configUri"
        if ($LASTEXITCODE -ne 0) { throw 'Backend stopped with an error. Check the log above.' }
    } finally {
        Pop-Location
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
