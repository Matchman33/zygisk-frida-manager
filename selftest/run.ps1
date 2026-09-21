# Offline self test for the manager's script crypto + generated loader.
#
#   powershell -File selftest\run.ps1
#   powershell -File selftest\run.ps1 -Cipher <cipher.so> -Plain <expected.js> -Key <key>
#
# Without input paths, the test generates a self-contained fixture.
# Needs a JDK and node on PATH. No Android device, no SDK.
# (ASCII only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI.)
param(
    [string]$Cipher = "",
    [string]$Plain = "",
    [string]$Key = "justcrackmenow"
)

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here "out"
$gen = Join-Path $here "gen"
Remove-Item -Recurse -Force $out, $gen -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out, $gen | Out-Null

$managerSrc = Join-Path $here "..\app\src\main\java\re\zyg\fri\manager"
$crypto = Join-Path $managerSrc "ScriptCrypto.java"
Write-Host "== 1/3 JVM: ScriptCrypto =="
& javac -encoding UTF-8 -d $out $crypto (Join-Path $here "ScriptCryptoTest.java")
if ($LASTEXITCODE -ne 0) { Write-Host "javac failed"; exit 1 }
if ($Cipher -and $Plain) {
    & java -cp $out ScriptCryptoTest $Cipher $Plain $Key $gen
    $expectedPlain = $Plain
} else {
    & java -cp $out ScriptCryptoTest --self-contained $Key $gen
    $expectedPlain = Join-Path $gen "expected.js"
}
$jvmExit = $LASTEXITCODE

Write-Host ""
Write-Host "== 2/3 JVM: SubConfig (sealed key, locked fields) =="
& javac -encoding UTF-8 -d $out $crypto (Join-Path $managerSrc "SubConfig.java") (Join-Path $here "SubConfigTest.java")
if ($LASTEXITCODE -ne 0) { Write-Host "javac failed"; exit 1 }
& java -cp $out SubConfigTest $gen
$subExit = $LASTEXITCODE

Write-Host ""
Write-Host "== 3/3 Node: run the generated loaders in a sandbox =="
& node (Join-Path $here "loader_harness.js") $gen $expectedPlain
$nodeExit = $LASTEXITCODE

Write-Host ""
if ($jvmExit -eq 0 -and $subExit -eq 0 -and $nodeExit -eq 0) {
    Write-Host "ALL PASS"
    exit 0
}
Write-Host "FAILURES (crypto=$jvmExit subconfig=$subExit loader=$nodeExit)"
exit 1
