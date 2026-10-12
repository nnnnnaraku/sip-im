<#
=====================================================================
 setup-mss.ps1  ——  miniSIPServer 一键配置脚本
=====================================================================

 为什么需要它？
   miniSIPServer 的部分配置在「重启后会丢失」（分机、会议室、服务触发记录都可能没）。
   本脚本通过 Web 管理接口，一键把服务器重新配好，不用再手工在界面上点。

 它做了 4 件事：
   1. 登录 Web 管理接口（拿到会话）
   2. 确保分机存在（默认 100 / 101），不存在则创建
   3. 创建会议室（默认编号 1）
   4. 注册会议室服务触发（*6* -> conference_room）
      ★ 这一步是关键：没有它，拨 *6*1 会被当成普通号码，返回 404

 用法（在项目目录下执行）：
   powershell -ExecutionPolicy Bypass -File setup-mss.ps1

 常用参数：
   -ServerHost 192.168.1.10     服务器 IP（默认 127.0.0.1）
   -ServerPort 8080             Web 管理端口
   -AdminPassword 1234          管理员密码
   -RoomList 1,2                要创建的会议室编号（逗号分隔）
   -ExtensionList 100,101       要确保存在的分机
   -SkipExtensions             跳过分机创建

 注意：需要 miniSIPServer 正在运行。
=====================================================================
#>
[CmdletBinding()]
param(
    [string]$ServerHost    = '127.0.0.1',
    [int]   $ServerPort    = 8080,
    [string]$AdminPassword = '1234',
    [string]$RoomList      = '1',
    [string]$ExtensionList = '100,101',
    [switch]$SkipExtensions
)

$ErrorActionPreference = 'Stop'
$base = "http://${ServerHost}:${ServerPort}"

# ------------------------------------------------------------------
# 工具函数
# ------------------------------------------------------------------

# 登录并返回会话 Cookie 字符串（形如 session=xxxx）
function New-MssSession {
    param([string]$Password)

    $url = "$base/authorize?password=" + [System.Uri]::EscapeDataString($Password)
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.AllowAutoRedirect = $false      # 必须关掉自动跳转，否则拿不到 Set-Cookie
    $req.Timeout = 8000

    $resp = $null
    try {
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        # 302 会被当成异常抛出，这里取回响应
        $resp = $_.Exception.Response
        if ($null -eq $resp) { throw "无法连接 $base —— miniSIPServer 正在运行吗？" }
    }

    $setCookie = $resp.Headers['Set-Cookie']
    $resp.Close()

    if ([string]::IsNullOrWhiteSpace($setCookie)) {
        throw "登录失败：没有拿到 session cookie。请检查 -AdminPassword 是否正确。"
    }
    return ($setCookie -split ';')[0].Trim()
}

# 调用 /dbajax 接口，返回 JSON 文本
function Invoke-MssApi {
    param(
        [string]$Session,
        [string]$Path,
        [string]$Json
    )

    $enc = [System.Uri]::EscapeDataString($Json)
    $url = "$base$Path" + "?content=" + $enc

    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Headers.Add('Cookie', $Session)
    $req.Timeout = 8000

    $resp = $req.GetResponse()
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
    $body = $reader.ReadToEnd()
    $reader.Close()
    $resp.Close()
    return $body
}

# 判断接口返回是否表示"列表为空"
# - MSS 用 {"errorCode":-2} 表示没有记录
# - 表为空时 localuser/list 可能直接返回 "fail"（非 JSON），也要当作空处理
# - 空字符串同样按空处理
function Test-NoRecord {
    param([string]$Json)
    if ([string]::IsNullOrWhiteSpace($Json)) { return $true }
    if ($Json -match '"errorCode"') { return $true }
    if ($Json.Trim() -notmatch '^[\[\{]') { return $true }
    return $false
}

function Write-Step  { param([string]$T) Write-Host "`n>>> $T" -ForegroundColor Cyan }
function Write-Ok    { param([string]$T) Write-Host "    [OK]   $T" -ForegroundColor Green }
function Write-Skip  { param([string]$T) Write-Host "    [跳过] $T" -ForegroundColor DarkGray }
function Write-Bad   { param([string]$T) Write-Host "    [失败] $T" -ForegroundColor Red }

# ------------------------------------------------------------------
# 主流程
# ------------------------------------------------------------------

Write-Host "==================================================" -ForegroundColor Yellow
Write-Host "  miniSIPServer 一键配置" -ForegroundColor Yellow
Write-Host "  服务器: $base" -ForegroundColor Yellow
Write-Host "==================================================" -ForegroundColor Yellow

# 1) 登录
Write-Step "1/5 登录 Web 管理接口"
$session = New-MssSession -Password $AdminPassword
Write-Ok "登录成功（$session）"

# 2) 分机
if (-not $SkipExtensions) {
    Write-Step "2/5 确保分机存在"
    $exts = $ExtensionList -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

    $existing = Invoke-MssApi -Session $session -Path '/dbajax/localuser/list' -Json '{"pageIndex":0}'
    $existingNames = @()
    if (-not (Test-NoRecord $existing)) {
        $existingNames = (ConvertFrom-Json $existing) | ForEach-Object { $_.usrname }
    }

    foreach ($ext in $exts) {
        # 注意：right_relay = "转发媒体流"（媒体中继）。
        # 不设这个权限时，服务器不再中转 RTP，两端会尝试直连；
        # 若两台电脑不在同一网段就会没声音、没画面。
        $payload = '{"usrname":"' + $ext + '","usrpwd":"' + $ext + '","dialplan":"default","description":"","right_relay":1,"endjson":"yes"}'
        $r = Invoke-MssApi -Session $session -Path '/dbajax/localuser/set' -Json $payload
        if ($r -match '"errorCode":0') {
            if ($existingNames -contains $ext) { Write-Ok "分机 $ext 已更新（含转发媒体流权限）" }
            else { Write-Ok "分机 $ext 已创建（密码同用户名）" }
        } else {
            Write-Bad "分机 $ext 返回: $r"
        }
    }
} else {
    Write-Step "2/5 分机（已按参数跳过）"
}

# 3) 会议室
Write-Step "3/5 创建会议室"
$rooms = $RoomList -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }

foreach ($room in $rooms) {
    $payload = '{"roomID":"' + $room + '","description":"course-room","password":"","endjson":"yes"}'
    $r = Invoke-MssApi -Session $session -Path '/dbajax/confroom/set' -Json $payload
    if ($r -match '"errorCode":0') { Write-Ok "会议室 $room 已配置（无密码）" }
    else { Write-Bad "会议室 $room 返回: $r" }
}

# 4) 会议室服务触发  ★ 关键步骤
Write-Step "4/5 注册会议室服务触发（拨号前缀 *6* -> conference_room）"
$payload = '{"dp":3,"dialPlan":"default","calledNbr":"*6*","description":"Conference room","luaName":"conference_room","endjson":"yes"}'
$r = Invoke-MssApi -Session $session -Path '/dbajax/luatrigger/set' -Json $payload
if ($r -match '"errorCode":0') { Write-Ok "触发已注册：拨 *6*<会议室编号> 即可入会" }
else { Write-Bad "触发注册返回: $r" }

# 5) 验证
Write-Step "5/5 校验结果"

$chk = Invoke-MssApi -Session $session -Path '/dbajax/luatrigger/list' -Json '{"pageIndex":0}'
if (Test-NoRecord $chk) {
    Write-Bad "Lua 触发表仍为空 —— 会议室拨号会失败！"
} else {
    Write-Ok "Lua 触发记录："
    (ConvertFrom-Json $chk) | ForEach-Object { Write-Host "         dp=$($_.dp)  dialPlan=$($_.dialPlan)  $($_.calledNbr)  ->  $($_.luaName)" }
}

$chk = Invoke-MssApi -Session $session -Path '/dbajax/confroom/list' -Json '{"pageIndex":0}'
if (Test-NoRecord $chk) {
    Write-Bad "会议室列表为空！"
} else {
    Write-Ok "会议室记录："
    (ConvertFrom-Json $chk) | ForEach-Object { Write-Host "         编号 $($_.roomID)   描述 $($_.description)   密码 '$($_.password)'" }
}

$chk = Invoke-MssApi -Session $session -Path '/dbajax/localuser/list' -Json '{"pageIndex":0}'
if (Test-NoRecord $chk) {
    Write-Bad "分机列表为空！"
} else {
    Write-Ok "分机记录："
    (ConvertFrom-Json $chk) | ForEach-Object {
        $st = if ($_.state -eq 1) { '在线' } else { '离线' }
        Write-Host "         $($_.usrname)   $st   $($_.addr)"
    }
}

Write-Host "`n==================================================" -ForegroundColor Yellow
Write-Host "  配置完成。客户端拨 *6*1 即可进入会议室。" -ForegroundColor Yellow
Write-Host "==================================================" -ForegroundColor Yellow
