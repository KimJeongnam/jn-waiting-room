[CmdletBinding()]
param(
    [string]$ServerAddress = $(if ($env:SERVER_ADDRESS) { $env:SERVER_ADDRESS } else { '127.0.0.1' }),
    [int]$ServerPort = $(if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }),
    [string]$FrontendHost = $(if ($env:FRONTEND_HOST) { $env:FRONTEND_HOST } else { '127.0.0.1' }),
    [int]$FrontendPort = $(if ($env:FRONTEND_PORT) { [int]$env:FRONTEND_PORT } else { 5173 }),
    [string]$BackendHost = $(if ($env:BACKEND_HOST) { $env:BACKEND_HOST } else { $ServerAddress }),
    [int]$BackendPort = $(if ($env:BACKEND_PORT) { [int]$env:BACKEND_PORT } else { $ServerPort })
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-HostName {
    param([string]$Name, [string]$Value)

    if ($Value -notmatch '^[A-Za-z0-9.:-]+$') {
        throw "$Name contains unsupported characters: $Value"
    }
}

function Assert-Port {
    param([string]$Name, [int]$Value)

    if ($Value -lt 1 -or $Value -gt 65535) {
        throw "$Name must be between 1 and 65535: $Value"
    }
}

Assert-HostName 'ServerAddress' $ServerAddress
Assert-HostName 'FrontendHost' $FrontendHost
Assert-HostName 'BackendHost' $BackendHost
Assert-Port 'ServerPort' $ServerPort
Assert-Port 'FrontendPort' $FrontendPort
Assert-Port 'BackendPort' $BackendPort

$repositoryPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
$wslPathPattern = '^\\\\wsl(?:\.localhost|\$)\\(?<distribution>[^\\]+)\\(?<path>.+)$'

if ($repositoryPath -notmatch $wslPathPattern) {
    throw 'This launcher expects the repository to be located in WSL.'
}

$distribution = $Matches.distribution
$linuxRepositoryPath = '/' + $Matches.path.Replace('\', '/')
$backendPath = "$linuxRepositoryPath/back-end"
$frontendPath = "$linuxRepositoryPath/front-end"

$frontendEntryUrl = "http://${FrontendHost}:$FrontendPort/demo/admitted"
$backendCommand = ". ~/.sdkman/bin/sdkman-init.sh; export SERVER_ADDRESS='$ServerAddress' SERVER_PORT='$ServerPort' SPRING_PROFILES_ACTIVE='demo' SPRING_DOCKER_COMPOSE_FILE='$linuxRepositoryPath/compose.yml' WAITING_ROOM_DEMO_JWT_ENABLED='true' WAITING_ROOM_DEMO_SEED_ENABLED='true' WAITING_ROOM_OAUTH2_ENABLED='true' WAITING_ROOM_RESERVATION_ENTRY_URL='$frontendEntryUrl'; bash gradlew :app:bootRun; exec bash"
$frontendCommand = ". ~/.nvm/nvm.sh; nvm use; export BACKEND_HOST='$BackendHost' BACKEND_PORT='$BackendPort' FRONTEND_HOST='$FrontendHost' FRONTEND_PORT='$FrontendPort'; pnpm dev; exec bash"

$terminalArguments = @(
    '-w', 'new',
    'new-tab', '--title', 'Waiting Room Backend',
    'wsl.exe', '-d', $distribution, '--cd', $backendPath, 'bash', '-lc', $backendCommand,
    ';',
    'new-tab', '--title', 'Waiting Room Frontend',
    'wsl.exe', '-d', $distribution, '--cd', $frontendPath, 'bash', '-lc', $frontendCommand
)

& wt.exe @terminalArguments
