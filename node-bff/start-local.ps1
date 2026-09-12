<#
.SYNOPSIS
  Runs node-bff locally (npm start) against local dev infra: spring-boot-api
  (:8081), Redis (:6379), Keycloak (:8080).

.DESCRIPTION
  node-bff has no dotenv dependency - it reads secrets straight from process
  env vars (see src/auth/oidc.js, src/auth/session.js) - so unlike
  docker-compose, `npm start` alone won't pick up the repo's root .env file.
  This script reads BFF_CLIENT_SECRET and SESSION_SECRET out of that .env
  (same file docker-compose reads) so the real secret isn't duplicated or
  hardcoded here, then sets the rest of the localhost-specific vars that
  docker-compose would otherwise supply via its own environment: block.

  Running locally (not in the docker network), the browser and node-bff both
  reach Keycloak via the same localhost:8080 URL, so KEYCLOAK_ISSUER and
  KEYCLOAK_ISSUER_PUBLIC are the same value here - unlike docker-compose.yml,
  where the container-internal and browser-facing hostnames differ.

.PARAMETER EnvFile
  Path to the .env file holding BFF_CLIENT_SECRET / SESSION_SECRET. Defaults
  to the repo root .env (see .env.example for how to populate it).
#>
param(
    [string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) ".env"),
    [string]$Port = "3000",
    [string]$ApiBaseUrl = "http://localhost:8081",
    [string]$RedisUrl = "redis://localhost:6379",
    [string]$KeycloakIssuer = "http://localhost:8080/realms/clinic",
    [string]$KeycloakClientId = "clinic-bff",
    [string]$BffBaseUrl = "http://localhost:3000"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $EnvFile)) {
    throw "No .env file found at '$EnvFile'. Copy .env.example to .env and fill in BFF_CLIENT_SECRET (see README/CLAUDE.md for how to get it from Keycloak) and SESSION_SECRET first."
}

$envValues = @{}
Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
        $envValues[$matches[1]] = $matches[2]
    }
}

foreach ($key in @("BFF_CLIENT_SECRET", "SESSION_SECRET")) {
    if (-not $envValues.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($envValues[$key]) -or $envValues[$key] -eq "changeme-in-keycloak-console" -or $envValues[$key] -eq "changeme-long-random-string") {
        throw "$key is missing or still a placeholder in '$EnvFile'. See .env.example for how to set it."
    }
}

$env:PORT = $Port
$env:API_BASE_URL = $ApiBaseUrl
$env:REDIS_URL = $RedisUrl
$env:KEYCLOAK_ISSUER = $KeycloakIssuer
$env:KEYCLOAK_ISSUER_PUBLIC = $KeycloakIssuer
$env:KEYCLOAK_CLIENT_ID = $KeycloakClientId
$env:KEYCLOAK_CLIENT_SECRET = $envValues["BFF_CLIENT_SECRET"]
$env:SESSION_SECRET = $envValues["SESSION_SECRET"]
$env:BFF_BASE_URL = $BffBaseUrl

Push-Location $PSScriptRoot
try {
    Write-Host "Starting node-bff on $BffBaseUrl (API: $ApiBaseUrl, Keycloak: $KeycloakIssuer) ..."
    npm start
}
finally {
    Pop-Location
}
