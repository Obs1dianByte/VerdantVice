#Requires -Version 7.0
<##
Runs the built distribution on an isolated real Paper server, twice.
No user world, plugin database or signing key is copied or modified.
An already accepted EULA is required; this script never accepts it for you.
##>
[CmdletBinding()]
param(
    [string] $JavaHome = $env:JAVA25_HOME,
    [string] $BootstrapJar,
    [string] $PluginJar,
    [string] $RuntimeCacheDirectory,
    [string] $EulaFile,
    [ValidateRange(60, 1800)] [int] $TimeoutSeconds = 300
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$resultDirectory = $null
$testStarted = [DateTime]::UtcNow
$previousTemporary = $env:TEMP
$previousTemporaryAlternative = $env:TMP
$javaTemporaryArguments = @()
$compilerTemporaryArguments = @()

function Resolve-RequiredFile([string] $Path, [string] $Description) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Invoke-IsolatedJava([string[]] $Arguments, [string] $Name) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $script:java
    $start.WorkingDirectory = $script:resultDirectory
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @($script:javaTemporaryArguments) + $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $stdout = [IO.FileStream]::new((Join-Path $script:resultDirectory "$Name.stdout.log"),
        [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
    $stderr = [IO.FileStream]::new((Join-Path $script:resultDirectory "$Name.stderr.log"),
        [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
    try {
        if (-not $process.Start()) { throw "Cannot start Java for $Name" }
        $stdoutCopy = $process.StandardOutput.BaseStream.CopyToAsync($stdout)
        $stderrCopy = $process.StandardError.BaseStream.CopyToAsync($stderr)
        $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while (-not $process.WaitForExit(500)) {
            if ([DateTime]::UtcNow -gt $deadline) {
                try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
                if (-not $process.WaitForExit(15000)) { $process.Kill($true); $process.WaitForExit() }
                throw "$Name timed out after $TimeoutSeconds seconds; logs: $script:resultDirectory"
            }
        }
        $stdoutCopy.GetAwaiter().GetResult() | Out-Null
        $stderrCopy.GetAwaiter().GetResult() | Out-Null
        if ($process.ExitCode -ne 0) { throw "$Name exited with code $($process.ExitCode)" }
    } finally {
        if ($process.Id -and -not $process.HasExited) {
            try { $process.StandardInput.WriteLine('stop'); $process.StandardInput.Flush() } catch { }
            if (-not $process.WaitForExit(15000)) { $process.Kill($true); $process.WaitForExit() }
        }
        $stdout.Dispose()
        $stderr.Dispose()
        $process.Dispose()
    }
}

function Write-TestReport([string[]] $Checks, [string] $Failure) {
    if (-not $script:resultDirectory) { return }
    $settings = [Xml.XmlWriterSettings]::new()
    $settings.Indent = $true
    $writer = [Xml.XmlWriter]::Create((Join-Path $script:resultDirectory 'junit.xml'), $settings)
    try {
        $writer.WriteStartDocument()
        $writer.WriteStartElement('testsuite')
        $writer.WriteAttributeString('name', 'VerdantVice.RealPaperSmoke')
        $writer.WriteAttributeString('tests', [string]($Checks.Count + [int](-not [string]::IsNullOrEmpty($Failure))))
        $writer.WriteAttributeString('failures', [string][int](-not [string]::IsNullOrEmpty($Failure)))
        $writer.WriteAttributeString('time', ([DateTime]::UtcNow - $script:testStarted).TotalSeconds.ToString('F3', [Globalization.CultureInfo]::InvariantCulture))
        foreach ($check in $Checks) {
            $writer.WriteStartElement('testcase')
            $writer.WriteAttributeString('name', $check)
            $writer.WriteAttributeString('classname', 'VerdantVice.RealPaperSmoke')
            $writer.WriteEndElement()
        }
        if ($Failure) {
            $writer.WriteStartElement('testcase')
            $writer.WriteAttributeString('name', 'server lifecycle and integration assertions')
            $writer.WriteStartElement('failure')
            $writer.WriteString($Failure)
            $writer.WriteEndElement()
            $writer.WriteEndElement()
        }
        $writer.WriteEndElement()
        $writer.WriteEndDocument()
    } finally { $writer.Dispose() }
}

$checks = [Collections.Generic.List[string]]::new()
try {
    $localTemporary = Join-Path $repository '.gradle/runtime-temp'
    if (Test-Path -LiteralPath $localTemporary -PathType Container) {
        $env:TEMP = $localTemporary
        $env:TMP = $localTemporary
        $javaTemporaryArguments = @("-Djava.io.tmpdir=$localTemporary")
        $compilerTemporaryArguments = @("-J-Djava.io.tmpdir=$localTemporary")
    }
    if (-not $PluginJar) { $PluginJar = Join-Path $repository 'verdantvice-paper/build/libs/VerdantVice-1.0.0.jar' }
    if (-not $RuntimeCacheDirectory) { $RuntimeCacheDirectory = Join-Path $repository 'run' }
    if (-not $EulaFile) { $EulaFile = Join-Path $RuntimeCacheDirectory 'eula.txt' }
    $PluginJar = Resolve-RequiredFile $PluginJar 'Built shaded plugin JAR (run the build first)'
    $EulaFile = Resolve-RequiredFile $EulaFile 'Previously accepted Minecraft EULA file'
    if (-not ((Get-Content -LiteralPath $EulaFile) -match '^\s*eula\s*=\s*true\s*$')) {
        throw 'The supplied EULA file is not accepted. Read and accept it yourself before running this test.'
    }
    if (-not $JavaHome) {
        $localJdk = Join-Path $repository '.gradle/runtime-tools'
        $foundJdk = @(Get-ChildItem -LiteralPath $localJdk -Directory -ErrorAction SilentlyContinue |
            Where-Object Name -Like 'jdk-25*' | Sort-Object Name -Descending)
        if ($foundJdk.Count) { $JavaHome = $foundJdk[0].FullName }
    }
    if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw 'Supply -JavaHome or JAVA25_HOME pointing to JDK 25' }
    $binarySuffix = if ($IsWindows) { '.exe' } else { '' }
    $java = Resolve-RequiredFile (Join-Path $JavaHome "bin/java$binarySuffix") 'Java 25 executable (-JavaHome or JAVA25_HOME)'
    $javac = Resolve-RequiredFile (Join-Path $JavaHome "bin/javac$binarySuffix") 'JDK compiler'
    $jar = Resolve-RequiredFile (Join-Path $JavaHome "bin/jar$binarySuffix") 'JDK jar tool'
    $javaVersion = (& $java @javaTemporaryArguments '-version' 2>&1 | Out-String)
    if ($javaVersion -notmatch 'version "25[.\-"]') { throw "Paper 26.2 smoke requires JDK 25; found $javaVersion" }

    if (-not $BootstrapJar) {
        $globalGradleCache = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
        $gradleCaches = @($env:GRADLE_USER_HOME, $globalGradleCache) |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
        $candidates = @(foreach ($gradleCache in $gradleCaches) {
            $bootstrapCache = Join-Path $gradleCache 'caches/run-task-jars/paper/jars/26.2'
            Get-ChildItem -LiteralPath $bootstrapCache -Filter '*.jar' -File -ErrorAction SilentlyContinue
        }) | Sort-Object { [int]$_.BaseName } -Descending
        $candidates = @($candidates)
        if ($candidates.Count) { $BootstrapJar = $candidates[0].FullName }
    }
    $BootstrapJar = Resolve-RequiredFile $BootstrapJar 'Paper 26.2 bootstrap JAR (-BootstrapJar)'
    $resultDirectory = Join-Path $repository ("build/paper-smoke/" + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Path $resultDirectory | Out-Null
    Write-Host "Isolated Paper integration tests: $resultDirectory"
    foreach ($cache in @('libraries', 'versions', 'cache')) {
        $source = Join-Path $RuntimeCacheDirectory $cache
        if (Test-Path -LiteralPath $source -PathType Container) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $resultDirectory $cache) -Recurse
        }
    }
    Copy-Item -LiteralPath $BootstrapJar -Destination (Join-Path $resultDirectory 'server.jar')
    Copy-Item -LiteralPath $EulaFile -Destination (Join-Path $resultDirectory 'eula.txt')
    $plugins = Join-Path $resultDirectory 'plugins'
    New-Item -ItemType Directory -Path $plugins | Out-Null
    $testedPluginJar = Join-Path $plugins 'VerdantVice.jar'
    Copy-Item -LiteralPath $PluginJar -Destination $testedPluginJar
    $testedPluginSha256 = (Get-FileHash -LiteralPath $testedPluginJar -Algorithm SHA256).Hash
    # Port 0 requests an ephemeral port; the test accepts no external connections.
    [IO.File]::WriteAllText((Join-Path $resultDirectory 'server.properties'), @'
server-ip=127.0.0.1
server-port=0
online-mode=true
enable-rcon=false
enable-query=false
max-players=1
level-name=smoke-world
level-type=minecraft:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false,"structure_overrides":[]}
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
pause-when-empty-seconds=-1
sync-chunk-writes=true
motd=Isolated VerdantVice automated test
'@)

    # Paperclip's --help extracts/verifies the runtime without opening a world.
    # If no cache was supplied, Paperclip may fetch its own required server files.
    Invoke-IsolatedJava @('-Xms256M', '-Xmx1024M', '-jar', 'server.jar', '--help') 'prepare'
    $classJars = @($testedPluginJar) + @(Get-ChildItem -LiteralPath (Join-Path $resultDirectory 'libraries') -Filter '*.jar' -File -Recurse | ForEach-Object FullName)
    $patchedJars = @(Get-ChildItem -LiteralPath (Join-Path $resultDirectory 'versions') -Filter '*.jar' -File -Recurse | ForEach-Object FullName)
    $classJars += $patchedJars
    $classPath = [string]::Join([IO.Path]::PathSeparator, $classJars)
    $classes = Join-Path $resultDirectory 'smoke-classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $argumentFile = Join-Path $resultDirectory 'javac.args'
    $compilerArguments = @('--release', '21', '-encoding', 'UTF-8', '-proc:none', '-classpath', $classPath,
        '-d', $classes, (Join-Path $PSScriptRoot 'paper-smoke/PaperSmokePlugin.java'))
    [IO.File]::WriteAllLines($argumentFile, @($compilerArguments | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' }))
    & $javac @compilerTemporaryArguments "@$argumentFile"
    if ($LASTEXITCODE -ne 0) { throw 'Real-server probe compilation failed' }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'paper-smoke/plugin.yml') -Destination (Join-Path $classes 'plugin.yml')
    & $jar @compilerTemporaryArguments '--create' '--file' (Join-Path $plugins 'VerdantViceSmoke.jar') '-C' $classes '.'
    if ($LASTEXITCODE -ne 0) { throw 'Real-server probe packaging failed' }

    foreach ($phase in @('first', 'second')) {
        Write-Host "Paper $phase start: real runtime assertions and graceful shutdown"
        Invoke-IsolatedJava @('-Xms256M', '-Xmx1024M', "-Dverdantvice.smoke.phase=$phase", '-Dpaper.disablePluginRemapping=true',
            '-jar', 'server.jar', '--nogui') $phase
        $result = Join-Path $plugins "VerdantViceSmoke/$phase.pass"
        if (-not (Test-Path -LiteralPath $result -PathType Leaf)) {
            throw "$phase did not produce a passing marker. Inspect $resultDirectory/$phase.stdout.log"
        }
        foreach ($check in Get-Content -LiteralPath $result) { $checks.Add("${phase}: $check") }
        $output = Get-Content -LiteralPath (Join-Path $resultDirectory "$phase.stdout.log") -Raw
        if ($output -notmatch 'Plugin enabled successfully\.' -or $output -notmatch 'schedulers and database connections are closed\.') {
            throw "$phase did not start and stop VerdantVice cleanly"
        }
        if ($output -notmatch 'SQLITE/RUNNING' -or $output -notmatch 'plants=0 farms=0 dbQueue=') {
            throw "$phase admin command output did not confirm running database and empty isolated gameplay state"
        }
        if ($output -match '\[(?:VerdantVice|VerdantViceSmoke)\].*(?:SMOKE FAIL|startup failed|tick failed)' -or
            $output -match '(?m)^\s+at .*com\.verdantvice\.') {
            throw "$phase logged a production exception"
        }
        $checks.Add("${phase}: console diagnostics and exception-free graceful lifecycle")
        if ($phase -eq 'second' -and $output -match 'Applying database migration') {
            throw 'Existing schema migrations were unexpectedly reapplied after restart'
        }
    }
    $backups = @(Get-ChildItem -LiteralPath (Join-Path $plugins 'VerdantVice/backups') -Filter '*-startup.db' -File)
    if (-not $backups.Count) { throw 'Restart did not create the configured SQLite startup backup' }
    $checks.Add('second: automatic SQLite startup backup exists')
    Write-TestReport -Checks $checks.ToArray() -Failure ''
    [IO.File]::WriteAllText((Join-Path $resultDirectory 'summary.json'), (@{
        status = 'passed'; assertions = $checks.Count; plugin = $testedPluginJar;
        sourcePluginPath = $PluginJar; pluginSha256 = $testedPluginSha256;
        bootstrap = (Join-Path $resultDirectory 'server.jar'); sourceBootstrapPath = $BootstrapJar;
        bootstrapSha256 = (Get-FileHash -LiteralPath (Join-Path $resultDirectory 'server.jar') -Algorithm SHA256).Hash;
        java = $java; javaVersion = $javaVersion.Trim(); durationSeconds = ([DateTime]::UtcNow - $testStarted).TotalSeconds
    } | ConvertTo-Json))
    Write-Host "PASS: $($checks.Count) real-server assertions. Report: $resultDirectory/junit.xml"
    exit 0
} catch {
    Write-TestReport -Checks $checks.ToArray() -Failure $_.Exception.ToString()
    [Console]::Error.WriteLine("Paper smoke FAILED: $($_.Exception.Message)")
    if ($resultDirectory) { [Console]::Error.WriteLine("Logs and report: $resultDirectory") }
    exit 1
} finally {
    $env:TEMP = $previousTemporary
    $env:TMP = $previousTemporaryAlternative
}
