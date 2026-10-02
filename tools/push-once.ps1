# One-shot push helper. The token is passed as a parameter (never written to git config,
# never embedded in the remote URL) and only used through GIT_CONFIG_* environment variables
# for the lifetime of this process.
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Remote = 'origin',
    [string]$Branch = 'master'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

$env:GIT_TERMINAL_PROMPT = '0'
$env:GIT_ASKPASS = ''

$basic = [Convert]::ToBase64String([System.Text.Encoding]::ASCII.GetBytes("x-access-token:$Token"))
# GIT_CONFIG_COUNT/KEY_n/VALUE_n inject config for this process only. The second entry shrinks
# http.postBuffer: this machine has postBuffer=524288000 globally, which makes git try to buffer
# the whole push in RAM and fail with "Out of memory, malloc failed".
$env:GIT_CONFIG_COUNT = '2'
$env:GIT_CONFIG_KEY_0 = 'http.extraHeader'
$env:GIT_CONFIG_VALUE_0 = "Authorization: Basic $basic"
$env:GIT_CONFIG_KEY_1 = 'http.postBuffer'
$env:GIT_CONFIG_VALUE_1 = '52428800'

Write-Host "==> git push $Remote $Branch" -ForegroundColor Cyan
git -c http.version=HTTP/1.1 push $Remote $Branch
if ($LASTEXITCODE -ne 0) { throw "git push failed with exit code $LASTEXITCODE" }

Remove-Item Env:GIT_CONFIG_VALUE_0, Env:GIT_CONFIG_VALUE_1 -ErrorAction SilentlyContinue
Remove-Item Env:GIT_CONFIG_KEY_0, Env:GIT_CONFIG_KEY_1 -ErrorAction SilentlyContinue
Remove-Item Env:GIT_CONFIG_COUNT -ErrorAction SilentlyContinue
Write-Host '==> push OK' -ForegroundColor Green
