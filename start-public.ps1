$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$CloudflaredPath = Join-Path $ProjectRoot "tools\cloudflared.exe"
$StdoutLog = Join-Path $ProjectRoot "cloudflared.stdout.log"
$StderrLog = Join-Path $ProjectRoot "cloudflared.stderr.log"
$ServerProcess = $null
$TunnelProcess = $null

try {
    Set-Location -LiteralPath $ProjectRoot

    if (-not (Test-Path -LiteralPath ".env")) {
        & (Join-Path $ProjectRoot "setup.ps1")
    }

    if (-not (Test-Path -LiteralPath $CloudflaredPath)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $CloudflaredPath) | Out-Null
        Write-Host "Telechargement de cloudflared depuis le depot officiel Cloudflare..."
        Invoke-WebRequest `
            -Uri "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe" `
            -OutFile $CloudflaredPath

    }

    $Signature = Get-AuthenticodeSignature -LiteralPath $CloudflaredPath
    if ($Signature.Status -ne "Valid" -or $Signature.SignerCertificate.Subject -notlike "*Cloudflare, Inc.*") {
        throw "La signature Authenticode de cloudflared n'est pas valide ou n'appartient pas a Cloudflare: $($Signature.Status)"
    }

    Write-Host "Compilation du serveur MCP..."
    & npm run build
    if ($LASTEXITCODE -ne 0) { throw "La compilation a echoue." }

    $ServerProcess = Start-Process `
        -FilePath (Get-Command node).Source `
        -ArgumentList @("dist/index.js") `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -PassThru

    $Ready = $false
    for ($Attempt = 0; $Attempt -lt 40; $Attempt++) {
        Start-Sleep -Milliseconds 250
        try {
            $Health = Invoke-RestMethod -Uri "http://127.0.0.1:3000/health" -TimeoutSec 1
            if ($Health.status -eq "ok") { $Ready = $true; break }
        } catch { }
        if ($ServerProcess.HasExited) { throw "Le serveur MCP s'est arrete au demarrage." }
    }
    if (-not $Ready) { throw "Le serveur MCP ne repond pas sur le port 3000." }

    Remove-Item -LiteralPath $StdoutLog, $StderrLog -Force -ErrorAction SilentlyContinue
    $TunnelProcess = Start-Process `
        -FilePath $CloudflaredPath `
        -ArgumentList @("tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:3000") `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $StdoutLog `
        -RedirectStandardError $StderrLog `
        -PassThru

    $PublicBaseUrl = $null
    for ($Attempt = 0; $Attempt -lt 120; $Attempt++) {
        Start-Sleep -Milliseconds 500
        $Logs = @()
        if (Test-Path -LiteralPath $StdoutLog) { $Logs += Get-Content -LiteralPath $StdoutLog -Raw }
        if (Test-Path -LiteralPath $StderrLog) { $Logs += Get-Content -LiteralPath $StderrLog -Raw }
        $Match = [regex]::Match(($Logs -join "`n"), "https://[a-z0-9-]+\.trycloudflare\.com")
        if ($Match.Success) { $PublicBaseUrl = $Match.Value; break }
        if ($TunnelProcess.HasExited) { throw "Le tunnel Cloudflare s'est arrete. Consultez cloudflared.stderr.log." }
    }
    if (-not $PublicBaseUrl) { throw "Cloudflare n'a pas fourni d'URL apres 60 secondes." }

    $ApiKey = ((Get-Content -LiteralPath ".env" | Where-Object { $_ -like "MCP_API_KEY=*" }) -split "=", 2)[1]
    Write-Host ""
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "URL A COLLER DANS NOTION:" -ForegroundColor Green
    Write-Host "$PublicBaseUrl/mcp" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Authentification par en-tete:" -ForegroundColor Green
    Write-Host "Nom: Authorization"
    Write-Host "Valeur: Bearer $ApiKey" -ForegroundColor Yellow
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "Laissez cette fenetre ouverte. Ctrl+C coupe le MCP et le tunnel."

    while (-not $ServerProcess.HasExited -and -not $TunnelProcess.HasExited) {
        Start-Sleep -Seconds 1
    }
    throw "Le serveur ou le tunnel s'est arrete."
}
finally {
    if ($TunnelProcess -and -not $TunnelProcess.HasExited) {
        Stop-Process -Id $TunnelProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($ServerProcess -and -not $ServerProcess.HasExited) {
        Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
    }
}
