#Requires -Version 7.0
<##
Adds actual optional-provider plugins and a separate integration probe to an
already prepared, stopped, loopback-only Minecraft client test server.
Provider JARs are not redistributed or downloaded by this script.
##>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $TestDirectory,
    [string] $ProviderDirectory,
    [string] $JavaHome = $env:JAVA25_HOME
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = (Resolve-Path -LiteralPath $TestDirectory).Path
$allowed = [IO.Path]::GetFullPath((Join-Path $repository 'build/minecraft-client'))
if (-not $testRoot.StartsWith($allowed + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Integration setup is restricted to a child of build/minecraft-client.'
}
$properties = Get-Content -LiteralPath (Join-Path $testRoot 'server.properties')
if (-not ($properties -match '^\s*server-ip\s*=\s*127\.0\.0\.1\s*$')) {
    throw 'Integration test server must bind to 127.0.0.1.'
}
$portLine = $properties | Where-Object { $_ -match '^server-port=' }
$port = [int]($portLine -replace '^server-port=', '')
$connection = [Net.Sockets.TcpClient]::new()
try {
    if ($connection.ConnectAsync('127.0.0.1', $port).Wait(150) -and $connection.Connected) {
        throw 'Stop the isolated test server before installing integration providers.'
    }
} catch [AggregateException] {
    # Connection refused is expected for the stopped isolated test server.
} finally { $connection.Dispose() }
if (-not $ProviderDirectory) { $ProviderDirectory = Join-Path $repository '.gradle/integration-plugins' }
if (-not $JavaHome) {
    $JavaHome = (Get-ChildItem -LiteralPath (Join-Path $repository '.gradle/runtime-tools') -Directory |
        Where-Object Name -Like 'jdk-25*' | Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$plugins = Join-Path $testRoot 'plugins'
$providers = @(
    @{file='Vault.jar'; sha256='A6B5ED97F43A5CF5BBAF00A7C8CD23C5AFC9BD003F849875AF8B36E6CF77D01D'},
    @{file='PlaceholderAPI.jar'; sha256='FDE03259F5AF6938F3C33EEB4D814000A1ADABF1D2304CE14970BE81F609A437'},
    @{file='EssentialsX.jar'; sha256='BDA4685105977FCA2E209820A9F0AD24275BD103390A03236F38E59BFDAC58E6'},
    @{file='worldedit-bukkit-7.4.6.jar'; sha256='E0CE67D870AD1FEF8A40DD9D7926360CAF3BB44722438545D6B20B55F82A0C91'},
    @{file='worldguard-bukkit-7.0.19.jar'; sha256='396DF065F1CF38BAB9878A0F53A776D5A085EBBF3C41813938B73F95D4EACA03'},
    @{file='GriefPrevention.jar'; sha256='45E9907C61222E559ED2E099FFEE0EDE706A21243A31D7DB549BAF678708FDF0'}
)
foreach ($provider in $providers) {
    $source = Join-Path $ProviderDirectory $provider.file
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $provider.sha256) {
        throw "Provider hash mismatch: $($provider.file). Use the exact versions documented in scripts/integration-smoke/README.md."
    }
}
foreach ($provider in $providers) {
    Copy-Item -LiteralPath (Join-Path $ProviderDirectory $provider.file) -Destination (Join-Path $plugins $provider.file)
}
$providerManifest = @{
    preparedUtc = [DateTime]::UtcNow.ToString('O')
    productionPluginSha256 = (Get-FileHash -LiteralPath (Join-Path $plugins 'VerdantVice.jar') -Algorithm SHA256).Hash
    providers = $providers
    scope = 'Prepared only. Actual player-join runtime must produce integration.pass before reporting success.'
}
[IO.File]::WriteAllText((Join-Path $testRoot 'integration-provider-manifest.json'), ($providerManifest | ConvertTo-Json -Depth 5))
$suffix = if ($IsWindows) { '.exe' } else { '' }
$javac = Join-Path $JavaHome "bin/javac$suffix"
$jar = Join-Path $JavaHome "bin/jar$suffix"
$classJars = @((Join-Path $plugins 'VerdantVice.jar'))
$classJars += Get-ChildItem -LiteralPath $plugins -Filter '*.jar' -File | ForEach-Object FullName
foreach ($directory in @('libraries','versions')) {
    $classJars += Get-ChildItem -LiteralPath (Join-Path $testRoot $directory) -Filter '*.jar' -File -Recurse | ForEach-Object FullName
}
$classPath = [string]::Join([IO.Path]::PathSeparator, @($classJars | Sort-Object -Unique))
$build = Join-Path $testRoot ('integration-probe-build/' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$argumentsFile = Join-Path $build 'javac.args'
$compilerArguments = @('--release','21','-encoding','UTF-8','-proc:none','-classpath',$classPath,
    '-d',$classes,(Join-Path $PSScriptRoot 'integration-smoke/IntegrationSmoke.java'))
[IO.File]::WriteAllLines($argumentsFile, @($compilerArguments | ForEach-Object {
    '"' + $_.Replace('\','/').Replace('"','\"') + '"'
}))
$temporary = Join-Path $repository '.gradle/runtime-temp'
$temporaryArguments = if (Test-Path -LiteralPath $temporary) { @("-J-Djava.io.tmpdir=$temporary") } else { @() }
& $javac @temporaryArguments "@$argumentsFile"
if ($LASTEXITCODE -ne 0) { throw 'Integration probe compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'integration-smoke/plugin.yml') -Destination (Join-Path $classes 'plugin.yml')
& $jar @temporaryArguments '--create' '--file' (Join-Path $plugins 'VerdantViceIntegrationProbe.jar') '-C' $classes '.'
if ($LASTEXITCODE -ne 0) { throw 'Integration probe packaging failed.' }
Write-Host 'Actual providers installed. Restart the isolated server and reconnect VVClientTest.'
Write-Host 'The integration probe starts automatically after joining. Results: plugins/VerdantViceIntegrationProbe/integration-results.json'
