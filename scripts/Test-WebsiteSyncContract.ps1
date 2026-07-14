param(
    [string]$SiteRepository = (Join-Path $PSScriptRoot "..\..\..\Other\enthusia-site"),
    [string]$LumaGuildsJar = $env:LUMAGUILDS_JAR
)

$ErrorActionPreference = "Stop"
$pluginRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$siteRoot = (Resolve-Path $SiteRepository).Path
$arguments = @("test", "--tests", "net.badgersmc.em.websync.WebsiteSyncContractFixtureTest")
if ($LumaGuildsJar) {
    $arguments = @("-Plumaguilds.jar=$LumaGuildsJar") + $arguments
}

Push-Location $pluginRoot
try {
    & .\gradlew.bat @arguments
    if ($LASTEXITCODE -ne 0) { throw "Plugin contract fixture generation failed." }
} finally {
    Pop-Location
}

$generated = Join-Path $pluginRoot "build\contract\website-sync"
$checkedIn = Join-Path $siteRoot "cloudflare\market-api\test\fixtures\plugin-contract"
foreach ($file in Get-ChildItem $generated -Filter "*.json") {
    $expected = Join-Path $checkedIn $file.Name
    if (-not (Test-Path $expected)) { throw "Missing checked-in Worker fixture: $($file.Name)" }
    if ((Get-FileHash $file.FullName -Algorithm SHA256).Hash -ne (Get-FileHash $expected -Algorithm SHA256).Hash) {
        throw "Plugin/Worker contract fixture drift: $($file.Name)"
    }
}

Push-Location (Join-Path $siteRoot "cloudflare\market-api")
try {
    & npm test
    if ($LASTEXITCODE -ne 0) { throw "Worker contract tests failed." }
    & npm run typecheck
    if ($LASTEXITCODE -ne 0) { throw "Worker typecheck failed." }
} finally {
    Pop-Location
}
