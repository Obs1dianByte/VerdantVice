#Requires -Version 7.0
<## One command for build, unit/database tests, distribution checks and real Paper smoke. ##>
[CmdletBinding()]
param(
    [string] $JavaHome = $env:JAVA25_HOME,
    [string] $BootstrapJar,
    [string] $RuntimeCacheDirectory,
    [string] $EulaFile,
    [ValidateRange(60, 1800)] [int] $TimeoutSeconds = 300,
    [switch] $SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$suffix = if ($IsWindows) { '.exe' } else { '' }
$candidates = [Collections.Generic.List[string]]::new()
foreach ($candidate in @($JavaHome, $env:JAVA_HOME)) {
    if (-not [string]::IsNullOrWhiteSpace($candidate)) { $candidates.Add($candidate) }
}
$localTools = Join-Path $repository '.gradle/runtime-tools'
foreach ($candidate in @(Get-ChildItem -LiteralPath $localTools -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Like 'jdk-25*' | Sort-Object Name -Descending)) {
    $candidates.Add($candidate.FullName)
}
$selected = $null
foreach ($candidate in $candidates) {
    $executable = Join-Path $candidate "bin/java$suffix"
    if (Test-Path -LiteralPath $executable -PathType Leaf) {
        $version = (& $executable '-version' 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0 -and $version -match 'version "25[.\-"]') { $selected = $candidate; break }
    }
}
if (-not $selected) {
    [Console]::Error.WriteLine('JDK 25 not found. Supply -JavaHome or JAVA25_HOME. No system settings were changed.')
    exit 1
}
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$previousGradleUserHome = $env:GRADLE_USER_HOME
$previousTemporary = $env:TEMP
$previousTemporaryAlternative = $env:TMP
try {
    $env:JAVA_HOME = $selected
    $env:PATH = (Join-Path $selected 'bin') + [IO.Path]::PathSeparator + $previousPath
    $localGradleHome = Join-Path $repository '.gradle/runtime-user-home'
    if (Test-Path -LiteralPath $localGradleHome -PathType Container) {
        $env:GRADLE_USER_HOME = $localGradleHome
    }
    $gradleArguments = @('build', 'verifyDistribution', '--no-configuration-cache')
    $localTemporary = Join-Path $repository '.gradle/runtime-temp'
    if (Test-Path -LiteralPath $localTemporary -PathType Container) {
        $env:TEMP = $localTemporary
        $env:TMP = $localTemporary
        $gradleArguments += "-Djava.io.tmpdir=$localTemporary"
    }
    Push-Location $repository
    try {
        if (-not $SkipBuild) {
            $wrapper = Join-Path $repository $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
            if ($IsWindows) { & $wrapper @gradleArguments }
            else { & bash $wrapper @gradleArguments }
            if ($LASTEXITCODE -ne 0) { throw "Build, tests or distribution verification failed ($LASTEXITCODE)" }
        }
        $arguments = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'Test-PaperSmoke.ps1'),
            '-JavaHome', $selected, '-TimeoutSeconds', [string]$TimeoutSeconds)
        if ($BootstrapJar) { $arguments += @('-BootstrapJar', $BootstrapJar) }
        if ($RuntimeCacheDirectory) { $arguments += @('-RuntimeCacheDirectory', $RuntimeCacheDirectory) }
        if ($EulaFile) { $arguments += @('-EulaFile', $EulaFile) }
        & (Join-Path $PSHOME "pwsh$suffix") @arguments
        if ($LASTEXITCODE -ne 0) { throw "Real Paper smoke failed ($LASTEXITCODE)" }
        Write-Host 'PASS: build, automated tests, distribution and real Paper restart checks.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    $env:GRADLE_USER_HOME = $previousGradleUserHome
    $env:TEMP = $previousTemporary
    $env:TMP = $previousTemporaryAlternative
}
exit 0
