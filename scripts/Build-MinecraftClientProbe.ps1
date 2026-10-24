#Requires -Version 7.0
<##
Compiles a test-only probe against an already prepared, isolated Minecraft server.
This script does not launch Minecraft, start/stop a server, run Gradle, or touch user worlds.
##>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $TestDirectory,
    [string] $JavaHome = $env:JAVA25_HOME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = (Resolve-Path -LiteralPath $TestDirectory -ErrorAction Stop).Path
$allowedRoot = [IO.Path]::GetFullPath((Join-Path $repository 'build/minecraft-client'))
if (-not $testRoot.StartsWith($allowedRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Probe installation is restricted to a child of the repository's build/minecraft-client folder: $testRoot"
}
$properties = Join-Path $testRoot 'server.properties'
if (-not (Test-Path -LiteralPath $properties -PathType Leaf) -or
    -not ((Get-Content -LiteralPath $properties) -match '^\s*server-ip\s*=\s*127\.0\.0\.1\s*$')) {
    throw 'The prepared test server must explicitly bind server-ip=127.0.0.1'
}
$pluginJar = Join-Path $testRoot 'plugins/VerdantVice.jar'
if (-not (Test-Path -LiteralPath $pluginJar -PathType Leaf)) { throw "Production plugin copy is missing: $pluginJar" }
if (-not $JavaHome) {
    $localJdks = @(Get-ChildItem -LiteralPath (Join-Path $repository '.gradle/runtime-tools') -Directory |
        Where-Object Name -Like 'jdk-25*' | Sort-Object Name -Descending)
    if ($localJdks.Count) { $JavaHome = $localJdks[0].FullName }
}
if (-not $JavaHome) { throw 'Supply -JavaHome or JAVA25_HOME pointing to JDK 25' }
$suffix = if ($IsWindows) { '.exe' } else { '' }
$javac = Join-Path $JavaHome "bin/javac$suffix"
$jar = Join-Path $JavaHome "bin/jar$suffix"
foreach ($binary in @($javac, $jar)) {
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw "Required JDK tool is missing: $binary" }
}
$classJars = @($pluginJar)
foreach ($directory in @('libraries', 'versions')) {
    $path = Join-Path $testRoot $directory
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { throw "Prepared Paper runtime directory is missing: $path" }
    $classJars += Get-ChildItem -LiteralPath $path -Filter '*.jar' -File -Recurse | ForEach-Object FullName
}
$classPath = [string]::Join([IO.Path]::PathSeparator, @($classJars | Sort-Object -Unique))
$buildRoot = Join-Path $testRoot ('client-probe-build/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildRoot 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$argumentsFile = Join-Path $buildRoot 'javac.args'
$probeSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'minecraft-client-probe') -Filter '*.java' -File |
    Sort-Object Name | ForEach-Object FullName)
$compilerArguments = @('--release', '21', '-encoding', 'UTF-8', '-proc:none', '-classpath', $classPath,
    '-d', $classes) + $probeSources
[IO.File]::WriteAllLines($argumentsFile, @($compilerArguments | ForEach-Object {
    '"' + $_.Replace('\', '/').Replace('"', '\"') + '"'
}))
$temporaryArguments = @()
$temporaryDirectory = Join-Path $repository '.gradle/runtime-temp'
if (Test-Path -LiteralPath $temporaryDirectory -PathType Container) {
    $temporaryArguments = @("-J-Djava.io.tmpdir=$temporaryDirectory")
}
& $javac @temporaryArguments "@$argumentsFile"
if ($LASTEXITCODE -ne 0) { throw 'Real Minecraft client probe compilation failed' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'minecraft-client-probe/plugin.yml') -Destination (Join-Path $classes 'plugin.yml')
$outputJar = Join-Path $testRoot 'plugins/VerdantViceClientProbe.jar'
& $jar @temporaryArguments '--create' '--file' $outputJar '-C' $classes '.'
if ($LASTEXITCODE -ne 0) { throw 'Real Minecraft client probe packaging failed' }
Write-Host "Built test-only real-client probe: $outputJar"
Write-Host 'After server restart, join as VVClientTest; /vvclient check; /vvclient menu MAIN; click real menus; /vvclient finish.'
Write-Host "English JSON/JUnit report: $(Join-Path $testRoot 'plugins/VerdantViceClientProbe')"
