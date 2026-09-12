<#
.SYNOPSIS
  Runs spring-boot-api locally (mvn spring-boot:run) against local dev infra:
  Postgres (:5432), Redis (:6379), Keycloak (:8080) - e.g. via
  `docker compose up postgres redis keycloak` or infra/keycloak/start-native.ps1.

.DESCRIPTION
  No mvnw wrapper is checked into this repo, so this uses `mvn` on PATH
  (Maven 3.9+ / Java 21). The values below just restate application.yml's own
  localhost defaults explicitly, so this script is self-documenting and easy
  to point at something else (e.g. a differently named local database)
  without editing application.yml.

.PARAMETER DbUrl
  JDBC URL for the app's own database (clinic_management - NOT Keycloak's db).
#>
param(
    [string]$DbUrl = "jdbc:postgresql://localhost:5432/clinic_management",
    [string]$DbUsername = "clinicops",
    [string]$DbPassword = "clinicops",
    [string]$RedisHost = "localhost",
    [string]$RedisPort = "6379",
    [string]$KeycloakIssuerUri = "http://localhost:8080/realms/clinic"
)

$ErrorActionPreference = "Stop"

$env:SPRING_DATASOURCE_URL = $DbUrl
$env:SPRING_DATASOURCE_USERNAME = $DbUsername
$env:SPRING_DATASOURCE_PASSWORD = $DbPassword
$env:SPRING_DATA_REDIS_HOST = $RedisHost
$env:SPRING_DATA_REDIS_PORT = $RedisPort
$env:KEYCLOAK_ISSUER_URI = $KeycloakIssuerUri
# Running natively (no docker network split), the browser and this process
# both reach Keycloak via the same localhost:8080 URL - see
# JwtDecoderConfig's javadoc for why these two are ever different at all.
$env:KEYCLOAK_ISSUER_PUBLIC_URI = $KeycloakIssuerUri

Push-Location $PSScriptRoot
try {
    Write-Host "Starting spring-boot-api against $DbUrl (Keycloak issuer: $KeycloakIssuerUri) ..."
    mvn spring-boot:run
}
finally {
    Pop-Location
}
