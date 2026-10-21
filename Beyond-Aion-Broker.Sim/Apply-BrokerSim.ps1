<#
Aion Living Broker - applies the broker simulation to an ALREADY BUILT, OFFLINE GameServer by recompiling the
changed source files against the server's own jars and updating the jar that contains BrokerService.

  .\Apply-BrokerSim.ps1 -GameServerRoot 'C:\...\Aion\Server\game-server'
  .\Apply-BrokerSim.ps1 -GameServerRoot 'C:\...\Aion\Server\game-server' -Restore     # put the original jar back
  .\Apply-BrokerSim.ps1 -GameServerRoot '...' -JdkHome 'C:\Path\To\JDK25'            # only if javac is not found automatically

Requirements: a JDK (not a JRE), the same major version the server runs with (Java 25 per the emulator README).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$GameServerRoot,
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$Restore
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path $GameServerRoot).Path

# 1. refuse to touch a running server
$running = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*$root*" }
if ($running) { throw "A Java process started from $root is running. Stop the GameServer first." }

# 2. find the jar that contains BrokerService
Add-Type -AssemblyName System.IO.Compression.FileSystem
$entry = 'com/aionemu/gameserver/services/BrokerService.class'
$jars = @(Get-ChildItem -Path $root -Recurse -Filter *.jar -File)
$serverJar = $null
foreach ($j in $jars) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($j.FullName)
    try { if ($z.GetEntry($entry)) { $serverJar = $j; break } } finally { $z.Dispose() }
}
if (-not $serverJar) { throw "No jar below $root contains $entry. Pass the real GameServer folder." }
Write-Host "Server jar: $($serverJar.FullName)"
$backup = "$($serverJar.FullName).before-brokersim"

if ($Restore) {
    if (-not (Test-Path $backup)) { throw "No backup found: $backup" }
    Copy-Item $backup $serverJar.FullName -Force
    Write-Host "Original jar restored."
    return
}

# 3. tools
if (-not $JdkHome) { # no JAVA_HOME: try the javac found on PATH
    $found = Get-Command javac -ErrorAction SilentlyContinue
    if ($found) { $JdkHome = Split-Path -Parent (Split-Path -Parent $found.Source) }
}
if (-not $JdkHome) { throw "javac not found. Install a JDK 25, or pass -JdkHome 'C:\Path\To\JDK25'." }
$javac = Join-Path $JdkHome 'bin\javac.exe'
$jarTool = Join-Path $JdkHome 'bin\jar.exe'
foreach ($t in $javac, $jarTool) { if (-not (Test-Path $t)) { throw "Not found: $t (a JDK is required, not a JRE)" } }
& $javac -version

# 4. config: its OWN folder. Never config\main - the server loads every file in there as global settings and crashes on unknown keys.
if (-not (Test-Path (Join-Path $root 'config'))) { throw "Expected config folder not found: $(Join-Path $root 'config')" }
$cfg = Join-Path $root 'config\broker-simulation'
New-Item -ItemType Directory -Force $cfg | Out-Null
# clean up files from the earlier (wrong) install location
foreach ($n in 'broker-simulation.properties', 'broker-simulation-items.tsv') {
    $old = Join-Path $root "config\main\$n"
    if (Test-Path $old) { Remove-Item $old -Force; Write-Host "Removed misplaced $old" }
}

# 5. backup (only the first time, so the backup is always the pristine jar)
if (-not (Test-Path $backup)) { Copy-Item $serverJar.FullName $backup; Write-Host "Backup: $backup" }

# 6. compile the 3 files against the server's own jars
$cp = ($jars | ForEach-Object { $_.FullName }) -join ';'
$sources = @(Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$out = Join-Path $env:TEMP ('brokersim-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $out | Out-Null
& $javac -encoding UTF-8 -nowarn -proc:none -cp $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed. Nothing was changed. Send me the error text." }

# 7. update the jar (adds BrokerSimulationService, replaces BrokerService*.class and PlayerService.class)
& $jarTool uf $serverJar.FullName -C $out .
if ($LASTEXITCODE -ne 0) {
    Copy-Item $backup $serverJar.FullName -Force
    throw "Updating the jar failed; the original jar was restored."
}
Remove-Item $out -Recurse -Force

# 8. config files (never overwrite existing ones)
foreach ($f in Get-ChildItem (Join-Path $here 'config\broker-simulation') -File) {
    $dest = Join-Path $cfg $f.Name
    if (Test-Path $dest) { Write-Host "Kept existing $dest" } else { Copy-Item $f.FullName $dest; Write-Host "Installed $dest" }
}
Write-Host "Done. Start the GameServer and look for 'Broker simulation started' in the log (about 15 s after the broker loads)."
