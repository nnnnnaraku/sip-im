# A 机部署与验证工作清单

> 面向：A 机操作同学（同时承担 **SIP 服务器管理员** + **客户端 100** + **后台管理端**）
> 本文件与《B 机部署与验证清单》配套，**两份合起来 = 本次实验的完整部署文档**
> 📖 项目总览 / 选型 / 架构 / 排障速查见 [README.md](README.md)

| 角色 | 机器 | IP | 分机 |
|---|---|---|---|
| **SIP 服务器**（miniSIPServer） | A 机 | `10.122.253.186` | 100 |
| 客户端 | B 机 | `10.21.138.31` | 101 |

> A 机一个顶三个角色：
> ① **miniSIPServer 服务器**（注册、呼叫路由、媒体中继）
> ② **网页聊天客户端**（分机 100，浏览器 8081）
> ③ **后台管理端**（Web 管理页面，浏览器 8090）
>
> ⚠️ 服务器管理员口令：`1234`

---

## 一、前置条件

- [ ] 已装 **JDK 8** 与 **Maven**
- [ ] 已安装 **miniSIPServer**（本机路径示例：`D:\学习\软产实践（二）\miniSIPServer\minisipserver.exe`）
- [ ] A 机能 `ping` 通 B 机
- [ ] 端口未被占用：`5060/UDP`、`8080/TCP`、`8081/TCP`、`8090/TCP`

---

## 二、部署并配置 SIP 服务器（A 机专属）

### 2.1 启动 miniSIPServer

- [ ] 双击 `minisipserver.exe`，托盘出现图标
- [ ] 浏览器打开 `http://127.0.0.1:8080/`，用口令 `1234` 能登录管理台

### 2.2 一键配置（分机 / 会议室 / **转发媒体流权限**）

在 `sipvideo` 目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File setup-mss.ps1 -ServerHost 127.0.0.1 -ServerPort 8080 -AdminPassword 1234 -ExtensionList 100,101,102 -RoomList 1
```

脚本会：
1. 登录 Web 管理接口
2. 确保分机 **100 / 101** 存在，并**带上「转发媒体流」权限**（`right_relay`）
3. 创建会议室 **1**
4. 注册服务触发 `*6*` → 会议室

- [ ] 输出里出现「分机 100/101 已更新（含转发媒体流权限）」
- [ ] 输出里出现会议室 1、触发 `*6*` 正常

> ⚠️ **「转发媒体流」是跨网段通话的关键**。
> A、B 不在同一网段时两端无法直连媒体，必须由服务器中转 RTP。
> 缺这个权限的表现是：**没声音、没画面、只能单向看见**。

### 2.3 服务器重启后必做

miniSIPServer 重启会**丢失**分机、会议室、服务触发、**「转发媒体流」权限**。
→ **每次重启后都要重跑 2.2 的脚本**。

---

## 三、获取代码

```powershell
git pull
```
（或整体拷贝 `sipvideo` 文件夹，不带 `target/`）

- [ ] 确认存在 `WebClientServer.java`、`AdminServer.java`、`MssClient.java`、`chat.html`、`dashboard.html`、`run-web.bat`、`run-admin.bat`、`setup-mss.ps1`

---

## 四、A 机 `config.properties`

在 `sipvideo` 目录下，确认（或新建）`config.properties`：

```properties
# ---------- 本机账号（A 机 = 100）----------
sip.username=100
sip.password=100

# ---------- SIP 服务器（就是本机）----------
sip.server.ip=10.122.253.186
sip.server.port=5060

# ---------- 本机 ----------
sip.local.ip=10.122.253.186
sip.local.port=5061
sip.register.interval.sec=300

# ---------- 对端（B 机 = 101）----------
sip.target.user=101

# ---------- 媒体 / 文件 / 视频 ----------
media.local.port=8000
im.file.chunk.size=800
im.voice.duration.sec=5
video.enabled=true
video.width=320
video.height=240
video.fps=10
video.quality.percent=50
video.local.port=8020

# ---------- 通话行为 ----------
call.duration.sec=180
call.auto.invite=false
app.keep.alive=true

# ---------- 网页客户端 ----------
web.port=8081

# ---------- 后台管理端 ----------
admin.port=8090
admin.password=1234

# ---------- 读 miniSIPServer 的在线状态/会议室（本机就填自身 IP）----------
mss.web.host=10.122.253.186
mss.web.port=8080
mss.web.password=1234
```

**要点：**
- `sip.local.ip` 必须是 **A 机当前 IP**，不能填 `127.0.0.1`
- A 机 IP 变了 → 改本文件，**并同步通知 B 改它的** `sip.server.ip`、`mss.web.host`
- `call.auto.invite=false`：A 也不自动拨号，靠按钮发起

---

## 五、启动 A 机的两个 Web 服务

### 5.1 网页聊天客户端（分机 100）

- [ ] 双击 **`run-web.bat`** → 出现 `✓ 注册成功!`（100）与 `访问地址: http://localhost:8081/`
- [ ] 浏览器打开 **http://localhost:8081/**

### 5.2 后台管理端（课程要求④）

- [ ] 双击 **`run-admin.bat`** → 出现 `访问地址: http://localhost:8090/`
- [ ] 浏览器打开 **http://localhost:8090/**，口令 `1234`

> 后台管理端包含：分机在线状态、会议室、通话记录统计、消息统计。
> 注意：**通话/消息统计读的是本机 `logs/events.tsv`**，只反映 A 机自己的事件；分机在线状态与会议室来自服务器（全局）。

### 5.3 启动前检查 `run-web.bat` / `run-admin.bat` 的路径

```bat
if not defined JAVA_HOME  set "JAVA_HOME=D:\dev\jdk8u504-b01"
if not defined MAVEN_HOME set "MAVEN_HOME=D:\dev\apache-maven-3.9.16"
```
与 A 机实际安装位置不一致就改（或把 JDK/Maven 加入系统 `PATH`）。

> ⚠️ 同一台机器**不要同时**开 `run.bat`（命令行客户端）和 `run-web.bat`（网页）——会抢同一个分机。

---

## 六、功能验证清单（A 机视角）

A 机浏览器 http://localhost:8081/ ，应与 B 机配合逐项确认。

- [ ] 顶部显示 `已注册`，`我 100 → 对端 101`
- [ ] 左侧联系人：**101 绿点在线**
- [ ] 文字：A 发 B 收；B 发 A 收
- [ ] 图片：发送后对端内联显示
- [ ] 视频消息：对端可播放
- [ ] 语音：点「🎤 录音」→说话→「⏹ 停止并发送」→ 对端可播放；**A 自己那条也能播放**
- [ ] 语音通话：接听后**只有声音、无画面**
- [ ] 视频通话：**双向画面 + 声音**
- [ ] 挂断同步：**A 挂断后 B 画面立即消失**

### 群聊（多方音视频，课程要求③）

- [ ] A 机跑 `setup-mss.ps1` 后，会议室 1 已就绪，拨 **`*6*1`** 即可入会
- [ ] 入会方式：把客户端的被叫改成 `sip.target.user=*6*1` 后启动
  - ⚠️ **当前网页客户端只支持点联系人拨号，暂不能直接拨 `*6*1`**；群聊演示请用命令行客户端（`run.bat`）
  - （如需网页端也支持拨会议室，可后续加一个"拨号输入框"）

### 后台管理端（课程要求④）

- [ ] 打开 http://localhost:8090/ ，口令 `1234`
- [ ] 看到：在线分机数、会议室、通话记录统计、消息统计
- [ ] 与 B 机通话后，统计数字有变化

---

## 七、端口与防火墙（B 机要能连到 A 机的这些端口）

| 端口 | 协议 | 用途 | B 是否需要访问 |
|---|---|---|---|
| 5060 | UDP | SIP 信令（注册/呼叫） | ✅ **需要** |
| 8080 | TCP | 服务器 Web 管理（B 读在线联系人） | ✅ **需要** |
| 5061 | UDP | A 客户端 SIP 端口 | ❌ |
| 8000 / 8020 | UDP | A 客户端音视频端口 | ❌（媒体由服务器中继，B 不直连） |
| 8081 | TCP | A 网页客户端 | ❌（仅 A 本机浏览器） |
| 8090 | TCP | A 后台管理端 | ❌（仅 A 本机浏览器） |

> 因为媒体走服务器中继，**B 只需要能访问 A 的 `5060/UDP` 和 `8080/TCP`**。

防火墙放行（**以管理员身份**运行 PowerShell）：

```powershell
New-NetFirewallRule -DisplayName "miniSIPServer SIP 5060" -Direction Inbound -Protocol UDP -LocalPort 5060 -Action Allow
New-NetFirewallRule -DisplayName "miniSIPServer Web 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

---

## 八、常见问题排查（服务器侧）

| 现象 | 根因 | 处理 |
|---|---|---|
| A/B 都注册不上 | 服务器没起 / 分机丢了 | 启动 miniSIPServer，跑 `setup-mss.ps1` |
| 通话**没声音、没画面、单向** | 分机缺**「转发媒体流」权限**（跨网段直连不通） | 跑 `setup-mss.ps1` |
| 新呼叫返回 `486 Busy Here` | 服务器有**僵尸通话**（客户端异常退出没挂断） | 重启 miniSIPServer，再跑 `setup-mss.ps1` |
| B 左侧没有联系人 | B 连不到 A 的 `8080` | 按第七节放行 TCP 8080；B 侧 `Test-NetConnection <A机IP> -Port 8080` |
| 分机重启后全消失 | 正常现象 | 重跑 `setup-mss.ps1` |
| 后台页面报"无法连接 miniSIPServer" | `mss.web.host/port/password` 不对 | 核对 `config.properties` |
| 后台统计一直是 0 | 本机还没有通话/消息事件 | 先做一次通话或发消息，`logs/events.tsv` 才会有数据 |

---

## 九、服务器重启后的恢复流程（照抄执行）

1. 启动 miniSIPServer，确认 `http://127.0.0.1:8080/` 能登录
2. 在 `sipvideo` 目录跑：
   ```powershell
   powershell -ExecutionPolicy Bypass -File setup-mss.ps1 -ServerHost 127.0.0.1 -ServerPort 8080 -AdminPassword 1234 -ExtensionList 100,101,102 -RoomList 1
   ```
3. 重启 A 机 `run-web.bat`（可选 `run-admin.bat`）
4. 通知 B 机重启它自己的 `run-web.bat`
5. 双方确认 `✓ 注册成功!` 后开始验证

---

## 十、交付

- [ ] A 机：miniSIPServer 运行 + 分机 100/101 + 会议室 1 + `*6*` 触发
- [ ] A 机：网页客户端（100，8081）正常注册
- [ ] A 机：后台管理端（8090）可打开、数据正常
- [ ] 第六节功能清单全部通过
- [ ] 截图/录屏留档，用于验收答辩

---

## 附：与 B 机文档的分工

| 内容 | A 机文档（本文） | B 机文档 |
|---|---|---|
| 服务器部署 / 配置 | ✅ | — |
| 「转发媒体流」权限 | ✅ | 说明依赖 |
| A 机客户端 100 | ✅ | — |
| B 机客户端 101 | 说明配合 | ✅ |
| 后台管理端 | ✅ | — |
| 两机功能验证 | ✅（A 视角） | ✅（B 视角） |
| 排查 | ✅（服务器侧） | ✅（客户端侧） |

两份文档合起来即本次实验的完整部署与验证说明。
