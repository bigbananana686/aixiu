param(
    [string]$Path = (Join-Path $PSScriptRoot '../../.env'),
    [ValidateRange(1024, 65535)] [int]$Port = 18080
)

$ErrorActionPreference = 'Stop'
$jar = Join-Path $PSScriptRoot '../auto-care-server/target/auto-care-server-0.0.1-SNAPSHOT.jar'
if (Test-Path -LiteralPath $Path) { throw 'The env file already exists. Keep it for its existing volumes.' }
if (-not (Test-Path -LiteralPath $jar)) { throw 'Package the backend with Maven first.' }

function New-HexSecret([int]$Size) {
    $bytes = [byte[]]::new($Size)
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$temporaryStub = [System.IO.Path]::GetTempFileName()
$temporaryJar = $temporaryStub + '.jar'
$demoPassword = New-HexSecret 12
try {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/spring-security-crypto-[^/]+\.jar$' }
        if (@($entry).Count -ne 1) { throw 'Expected one Spring Security crypto jar in the application artifact.' }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $temporaryJar)
    } finally { $archive.Dispose() }

    $hash = $demoPassword | java -cp $temporaryJar (Join-Path $PSScriptRoot 'SeedPasswordHash.java')
    if ($LASTEXITCODE -ne 0 -or $hash -cnotmatch '^\$2[aby]\$10\$[./A-Za-z0-9]{53}$') {
        throw 'Unable to generate a valid BCrypt seed password hash.'
    }
} finally {
    if (Test-Path -LiteralPath $temporaryJar) { Remove-Item -LiteralPath $temporaryJar }
    Remove-Item -LiteralPath $temporaryStub
}

$content = @(
    "APP_HTTP_PORT=$Port"
    "MYSQL_ROOT_PASSWORD=$(New-HexSecret 32)"
    "DB_PASSWORD=$(New-HexSecret 32)"
    "JWT_SECRET=$(New-HexSecret 32)"
    "SEED_PASSWORD_HASH='$hash'"
    "AIXIU_DEMO_PASSWORD=$demoPassword"
)
New-Item -ItemType File -Path $Path -Value ($content -join "`n") | Out-Null
Write-Output "Created $Path. AIXIU_DEMO_PASSWORD is for the local seeded accounts."
