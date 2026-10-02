# Query GitHub Actions for this repository and (optionally) download the latest build artifact.
#
# The token is a parameter only; it is never written to disk or to git config.
#
# Usage:
#   pwsh -File tools/actions.ps1 -Token <pat>                 # list recent runs
#   pwsh -File tools/actions.ps1 -Token <pat> -Wait           # wait for the newest run to finish
#   pwsh -File tools/actions.ps1 -Token <pat> -Download      # download artifacts of the newest run

param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$Repo = 'SenJack-Zhu/termux-float',
    [switch]$Wait,
    [switch]$Download,
    [string]$OutDir = 'artifacts'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$headers = @{
    Authorization          = "Bearer $Token"
    'User-Agent'           = 'termux-float-build-check'
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}

function Get-Runs {
    (Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/actions/runs?per_page=5" -Headers $headers -TimeoutSec 60).workflow_runs
}

$runs = Get-Runs
if (-not $runs -or $runs.Count -eq 0) { Write-Host 'No workflow runs found.' -ForegroundColor Yellow; exit 1 }

Write-Host '== recent workflow runs ==' -ForegroundColor Cyan
foreach ($r in $runs) {
    Write-Host ("  {0,-28} {1,-12} {2,-10} {3}" -f $r.name, $r.head_sha.Substring(0, 7), "$($r.status)/$($r.conclusion)", $r.html_url)
}

$latest = $runs[0]
if ($Wait) {
    Write-Host "== waiting for run $($latest.id) ==" -ForegroundColor Cyan
    $deadline = (Get-Date).AddMinutes(25)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 20
        $run = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/actions/runs/$($latest.id)" -Headers $headers -TimeoutSec 60
        Write-Host ("  status={0} conclusion={1}" -f $run.status, $run.conclusion)
        if ($run.status -eq 'completed') { $latest = $run; break }
    }
    if ($latest.status -ne 'completed') { throw 'Timed out waiting for the workflow run to finish.' }
    if ($latest.conclusion -ne 'success') {
        Write-Host "Workflow concluded with '$($latest.conclusion)' - fetching failed job logs" -ForegroundColor Red
        $jobs = (Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/actions/runs/$($latest.id)/jobs" -Headers $headers -TimeoutSec 60).jobs
        foreach ($j in $jobs) {
            Write-Host ("  job '{0}': {1}" -f $j.name, $j.conclusion) -ForegroundColor Red
            foreach ($s in $j.steps) { if ($s.conclusion -ne 'success') { Write-Host ("    step '{0}': {1}" -f $s.name, $s.conclusion) -ForegroundColor Yellow } }
        }
        exit 2
    }
}

if ($Download) {
    $dest = Join-Path $root $OutDir
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    $arts = (Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/actions/runs/$($latest.id)/artifacts" -Headers $headers -TimeoutSec 60).artifacts
    if (-not $arts) { Write-Host 'No artifacts on this run.' -ForegroundColor Yellow; exit 1 }
    foreach ($a in $arts) {
        if ($a.expired) { Write-Host "  artifact '$($a.name)' expired, skipping" -ForegroundColor Yellow; continue }
        $zip = Join-Path $dest "$($a.name).zip"
        Write-Host "== downloading artifact '$($a.name)' ($($a.size_in_bytes) bytes) ==" -ForegroundColor Cyan
        Invoke-WebRequest -Uri "https://api.github.com/repos/$Repo/actions/artifacts/$($a.id)/zip" -Headers $headers -OutFile $zip -TimeoutSec 600
        Write-Host "  -> $zip"
    }
    Get-ChildItem $dest | Select-Object Name, Length | Format-Table -AutoSize
}
