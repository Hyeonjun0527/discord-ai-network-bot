param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Scope
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $PSCommandPath
$RepoRoot = Split-Path -Parent $ScriptDir
Set-Location $RepoRoot
$CentralJavaHome = if ($env:NEXA_JAVA_HOME) { $env:NEXA_JAVA_HOME } else { "/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home" }

function Show-Usage {
    @"
사용법: ./scripts/nexa-verify.ps1 <scope> [scope...]

scope:
  docs      task graph, NEXA fixture, 문서 링크, diff 공백 검사
  central   central-server build
  agent     provider-agent pytest/ruff/mypy
  i18n      i18n SSOT completeness + generated artifact drift
  protocol  wire contract drift + 양측 contract 테스트
  contracts protocol 과 동일한 alias
  all       docs, central, agent, protocol 순서로 모두 실행
"@ | Write-Host
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Command,
        [string[]] $Arguments = @()
    )

    $line = "+ " + (($Command, $Arguments) -join " ")
    Write-Host $line.TrimEnd()
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

function Get-VenvCommand {
    param([Parameter(Mandatory = $true)] [string] $Name)

    $posixPath = Join-Path ".venv/bin" $Name
    $windowsPath = Join-Path ".venv/Scripts" "$Name.exe"
    if (Test-Path $posixPath) {
        return $posixPath
    }
    if (Test-Path $windowsPath) {
        return $windowsPath
    }
    return $posixPath
}

function Verify-Docs {
    Invoke-External "python3" @("scripts/validate-nexa-task-graph.py")
    Invoke-External "python3" @("scripts/central-package-graph.py", "--check")
    Invoke-External "python3" @("scripts/validate-nexa-conversation-fixtures.py")
    Invoke-External "python3" @("scripts/check_links.py")
    Invoke-External "git" @("diff", "--check")
}

function Verify-Central {
    Invoke-WithCentralJavaHome { Invoke-External "make" @("central-build") }
}

function Verify-Agent {
    Push-Location "provider-agent"
    try {
        Invoke-External "../$(Get-VenvCommand 'python')" @("-m", "pytest", "-q", "--cov=provider_agent", "--cov-fail-under=70")
        Invoke-External "../$(Get-VenvCommand 'ruff')" @("check", "src", "tests")
        Invoke-External "../$(Get-VenvCommand 'mypy')" @("src")
    }
    finally {
        Pop-Location
    }
}

function Verify-I18n {
    Invoke-External "make" @("i18n-check")
}

function Invoke-WithCentralJavaHome {
    param([Parameter(Mandatory = $true)] [scriptblock] $Action)

    $previousJavaHome = $env:JAVA_HOME
    $env:JAVA_HOME = $CentralJavaHome
    try {
        & $Action
    }
    finally {
        if ($null -eq $previousJavaHome) {
            Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
        }
        else {
            $env:JAVA_HOME = $previousJavaHome
        }
    }
}

function Verify-Protocol {
    Invoke-WithCentralJavaHome { Invoke-External "make" @("contract") }
}

function Verify-All {
    Verify-Docs
    Verify-Central
    Verify-Agent
    Verify-I18n
    Verify-Protocol
}

function Invoke-Scope {
    param([Parameter(Mandatory = $true)] [string] $Name)

    switch ($Name) {
        "docs" { Verify-Docs; break }
        "central" { Verify-Central; break }
        "agent" { Verify-Agent; break }
        "i18n" { Verify-I18n; break }
        "protocol" { Verify-Protocol; break }
        "contracts" { Verify-Protocol; break }
        "all" { Verify-All; break }
        { $_ -in @("-h", "--help", "help") } { Show-Usage; break }
        default {
            Show-Usage
            Write-Error "알 수 없는 scope: $Name"
            exit 2
        }
    }
}

if ($null -eq $Scope -or $Scope.Count -eq 0) {
    Show-Usage
    exit 2
}

foreach ($name in $Scope) {
    Invoke-Scope $name
}
