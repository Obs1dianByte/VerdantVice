#Requires -Version 7.0
<##
Prepares an isolated loopback-only Paper server and starts an already installed
Minecraft Java client. No launcher account, access token, user world or options
file is read or changed. A disposable offline identity is used only on localhost.
Start the prepared server separately, then pass -LaunchClient -TestDirectory.
No software is downloaded and no EULA is accepted by this script.
##>
[CmdletBinding()]
param(
    [string] $MinecraftDirectory = (Join-Path $env:APPDATA '.minecraft'),
    [string] $JavaHome,
    [string] $TestDirectory,
    [switch] $LaunchClient
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JavaHome) {
    $JavaHome = (Get-ChildItem -LiteralPath (Join-Path $repository '.gradle/runtime-tools') -Directory |
        Where-Object Name -Like 'jdk-25*' | Sort-Object Name -Descending | Select-Object -First 1).FullName
}
$java = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'A local JDK 25 is required.' }
$metadataPath = Join-Path $MinecraftDirectory 'versions/26.2/26.2.json'
$metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json -AsHashtable
$clientJar = Join-Path $MinecraftDirectory 'versions/26.2/26.2.jar'
if ((Get-FileHash -LiteralPath $clientJar -Algorithm SHA1).Hash -ne $metadata.downloads.client.sha1) {
    throw 'The installed client JAR does not match its version metadata.'
}

if (-not $LaunchClient) {
    $acceptedEula = Join-Path $repository 'run/eula.txt'
    if (-not ((Get-Content -LiteralPath $acceptedEula) -match '^\s*eula\s*=\s*true\s*$')) {
        throw 'A previously accepted run/eula.txt is required. This script does not accept EULA.'
    }
    $TestDirectory = Join-Path $repository ('build/minecraft-client/' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $TestDirectory | Out-Null
    foreach ($cache in @('libraries', 'versions', 'cache')) {
        $source = Join-Path $repository "run/$cache"
        if (Test-Path -LiteralPath $source -PathType Container) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $TestDirectory $cache) -Recurse
        }
    }
    $bootstrap = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle/caches/run-task-jars/paper/jars/26.2/87.jar'
    Copy-Item -LiteralPath $bootstrap -Destination (Join-Path $TestDirectory 'server.jar')
    Copy-Item -LiteralPath $acceptedEula -Destination (Join-Path $TestDirectory 'eula.txt')
    New-Item -ItemType Directory -Path (Join-Path $TestDirectory 'plugins') | Out-Null
    Copy-Item -LiteralPath (Join-Path $repository 'verdantvice-paper/build/libs/VerdantVice-1.0.0.jar') -Destination (Join-Path $TestDirectory 'plugins/VerdantVice.jar')
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    [IO.File]::WriteAllText((Join-Path $TestDirectory 'server.properties'), @"
server-ip=127.0.0.1
server-port=$port
online-mode=false
enforce-secure-profile=false
enable-rcon=false
enable-query=false
max-players=1
level-name=client-test-world
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false,"structure_overrides":[]}
generate-structures=false
spawn-protection=0
view-distance=3
simulation-distance=2
pause-when-empty-seconds=-1
gamemode=survival
difficulty=peaceful
sync-chunk-writes=true
motd=VerdantVice isolated client verification
"@)
    $clientDirectory = Join-Path $TestDirectory 'client'
    New-Item -ItemType Directory -Path (Join-Path $clientDirectory 'resourcepacks') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repository 'verdantvice-paper/build/resource-packs/VerdantVice-resourcepack-26.2.zip') -Destination (Join-Path $clientDirectory 'resourcepacks/VerdantVice.zip')
    [IO.File]::WriteAllText((Join-Path $clientDirectory 'options.txt'), @'
lang:en_us
guiScale:2
renderDistance:3
simulationDistance:5
maxFps:60
fullscreen:false
onboardAccessibility:false
skipMultiplayerWarning:true
resourcePacks:["vanilla","file/VerdantVice.zip"]
'@)
    [IO.File]::WriteAllText((Join-Path $TestDirectory 'client-test.json'), (@{
        directory=$TestDirectory; port=$port; clientDirectory=$clientDirectory;
        minecraftVersion='26.2'; java=$java; clientJarSha1=$metadata.downloads.client.sha1;
        pluginSha256=(Get-FileHash -LiteralPath (Join-Path $TestDirectory 'plugins/VerdantVice.jar') -Algorithm SHA256).Hash;
        identity='VVClientTest'; scope='Loopback-only, disposable offline test identity; no account credentials.'
    } | ConvertTo-Json))
    & (Join-Path $PSScriptRoot 'Build-MinecraftClientProbe.ps1') -TestDirectory $TestDirectory -JavaHome $JavaHome
    Get-Content -LiteralPath (Join-Path $TestDirectory 'client-test.json') -Raw
    exit 0
}

$manifest = Get-Content -LiteralPath (Join-Path $TestDirectory 'client-test.json') -Raw | ConvertFrom-Json
if (Test-Path -LiteralPath (Join-Path $TestDirectory 'client.pid')) {
    $existingPid = [int](Get-Content -LiteralPath (Join-Path $TestDirectory 'client.pid'))
    if (Get-Process -Id $existingPid -ErrorAction SilentlyContinue) {
        throw "A client process already exists for this test directory (PID $existingPid)."
    }
}
$classJars = [Collections.Generic.List[string]]::new()
foreach ($library in $metadata.libraries) {
    $allowed = -not $library.ContainsKey('rules')
    $rules = if ($library.ContainsKey('rules')) { $library.rules } else { @() }
    foreach ($rule in $rules) {
        $matches = $true
        if ($rule.ContainsKey('os')) {
            if ($rule.os.ContainsKey('name') -and $rule.os.name -ne 'windows') { $matches = $false }
            if ($rule.os.ContainsKey('arch') -and $rule.os.arch -notin @('x86_64','amd64')) { $matches = $false }
        }
        if ($matches) { $allowed = $rule.action -eq 'allow' }
    }
    if (-not $allowed) { continue }
    $artifact = $library.downloads.artifact
    $path = Join-Path $MinecraftDirectory ('libraries/' + $artifact.path)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing installed client library: $($artifact.path)" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA1).Hash -ne $artifact.sha1) { throw "Client library hash mismatch: $($artifact.path)" }
    $classJars.Add($path)
}
$classJars.Add($clientJar)
$nativeDirectory = Join-Path $manifest.clientDirectory 'natives'
New-Item -ItemType Directory -Path $nativeDirectory -Force | Out-Null
$arguments = @('-Xms256M','-Xmx1536M','--enable-native-access=ALL-UNNAMED','--sun-misc-unsafe-memory-access=allow',
    "-Djava.io.tmpdir=$nativeDirectory", "-Djava.library.path=$nativeDirectory/java", "-Djna.tmpdir=$nativeDirectory/jna",
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=$nativeDirectory/lwjgl", "-Dio.netty.native.workdir=$nativeDirectory/netty",
    '-Dminecraft.launcher.brand=VerdantViceLocalTest','-Dminecraft.launcher.version=1.0',
    '-cp',[string]::Join([IO.Path]::PathSeparator,$classJars), $metadata.mainClass,
    '--username','VVClientTest','--version','26.2','--gameDir',$manifest.clientDirectory,
    '--assetsDir',(Join-Path $MinecraftDirectory 'assets'),'--assetIndex',$metadata.assetIndex.id,
    '--uuid','00000000000000000000000000000001','--accessToken','0',
    '--versionType','release','--width','1280','--height','720','--quickPlayMultiplayer',"127.0.0.1:$($manifest.port)")
$argumentFile = Join-Path $manifest.clientDirectory 'launch.args'
[IO.File]::WriteAllLines($argumentFile, @($arguments | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }))
# javaw opens only the game window; the server is started independently.
$javaw = Join-Path $JavaHome 'bin/javaw.exe'
$clientProcess = Start-Process -FilePath $javaw -ArgumentList ('"@' + $argumentFile + '"') -WorkingDirectory $manifest.clientDirectory -PassThru
[IO.File]::WriteAllText((Join-Path $TestDirectory 'client.pid'), [string]$clientProcess.Id)
Write-Host "Minecraft client PID $($clientProcess.Id); isolated game directory: $($manifest.clientDirectory)"
