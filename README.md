# 基于 SIP 的即时通信软件（PC 客户端 + 后台管理端）

> 北京邮电大学《软件产品综合研发实践（阶段二）》分组 03
> 一个用 **Java** 从零实现的即时通信系统：SIP 信令 + 自研 RTP 媒体 + 网页客户端 + Web 后台管理端。
>
> ⚠️ **自行部署前必读**：仓库里的 IP、分机号、口令都是**作者环境的示例值**。
> 部署时请把 `config.properties.example` 复制为 `config.properties`，并改成**你自己的环境**：
> - `sip.local.ip` → **运行本客户端这台机器的 IP**（`ipconfig` / `Get-NetIPAddress` 查看）
> - `sip.server.ip` → **跑 miniSIPServer 那台机器的 IP**
>
> 详见第 3.1 节「部署参数」与第 7 节「快速开始」。

---

## 目录

1. [项目背景与课程要求](#1-项目背景与课程要求)
2. [技术选型与理由](#2-技术选型与理由)
3. [系统架构](#3-系统架构)
4. [功能实现现状](#4-功能实现现状)
5. [目录结构与关键类](#5-目录结构与关键类)
6. [环境要求](#6-环境要求)
7. [快速开始](#7-快速开始)
8. [使用说明](#8-使用说明)
9. [配置项说明](#9-配置项说明)
10. [已知限制](#10-已知限制)
11. [工程要点与踩坑记录](#11-工程要点与踩坑记录)
12. [进度与后续计划](#12-进度与后续计划)
13. [文档索引](#13-文档索引)

---

## 1. 项目背景与课程要求

课程要求部署 **Kamailio** 或 **miniSIPServer** 之一作为 SIP 服务器，并**用 Java 实现一个即时通信软件（PC 客户端 + 后台管理端）**，具体四项功能：

| # | 课程要求 | 说明 |
|---|---|---|
| ① | 基于 SIP 的音视频通话 | 点对点语音 / 视频通话 |
| ② | 文字、图片、语音、视频的发送和接收 | 四类消息 |
| ③ | 群聊（多方音视频通话） | 多方会议 |
| ④ | 后台管理与查询统计 | 功能自行设计 |

**验收要求**：第 12 周答辩，共 25 分钟（PPT 10 分钟 + 系统演示与交流 15 分钟）；
⭐ **系统演示必须在 2 台电脑上进行**；材料需在 **2026-11-20 24:00** 前提交
（小组实验报告、个人实践报告、汇报 PPT、可运行软件、源代码）。

---

## 2. 技术选型与理由

### 2.1 SIP 服务器：miniSIPServer

| 对比项 | miniSIPServer | Kamailio |
|---|---|---|
| 部署 | Windows 安装即用，带 GUI | 需 Linux + 大量配置 |
| 管理 | 自带 Web 管理接口（`/dbajax/*`） | 主要靠配置文件/命令行 |
| 内置能力 | **会议室**、CDR、媒体中继 | 需自行配置模块 |
| 结论 | ✅ **选用**（快速出成果、便于演示） | 备选 |

### 2.2 Java SIP 协议栈：JAIN SIP 1.3.0-91

- `javax.sip` 标准 API，**纯 Java 无需编译原生库**，与 JDK 无缝集成
- 提供完整协议栈：消息解析、事务处理、对话管理
- 课程 PPT 中推荐的三个库之一（另两个是 PJSIP、Mobicents SIP Servlets）

### 2.3 媒体层：自研（不依赖第三方媒体库）

| 媒体 | 方案 |
|---|---|
| 语音 | **PCMU / G.711 μ-law**（PT 0，8000 Hz），20ms 一包 |
| 视频 | **MJPEG over RTP**（PT 26，RFC 2435 简化版），JDK 自带 `ImageIO` 编 JPEG |
| 摄像头采集 | `webcam-capture` 0.3.12 |
| 采集/播放 | `javax.sound.sampled` |

> 选 MJPEG 而不是 H.264：**编解码用 JDK 自带 ImageIO 即可**，无需引入 FFmpeg 等重型依赖，实现可控、便于调试。

### 2.4 消息：SIP MESSAGE（RFC 3428）+ 自定义分片协议

- 文字消息直接用 `MESSAGE` 的 body
- 图片/语音/视频消息用自定义应用层分片协议（见 [5.2](#52-文件消息分片协议)）

### 2.5 前端与服务端桥接：JDK 内置能力，零额外依赖

- 网页客户端 / 后台管理端都基于 JDK 自带的 `com.sun.net.httpserver.HttpServer`
- 网页实时推送用 **SSE（Server-Sent Events）**
- JSON 解析用 **Gson**

---

## 3. 系统架构

```
            ┌────────────────────────────────────────────┐
            │        A 机：miniSIPServer（SIP 服务器）      │
            │  注册 / 呼叫路由 / 会议室 / RTP 媒体中继       │
            │  SIP 5060/UDP      Web 管理 8080/TCP          │
            └───────┬────────────────────────────┬────────┘
                    │ SIP 信令                    │ SIP 信令
                    │                            │
      ┌─────────────▼─────────────┐   ┌──────────▼─────────────────┐
      │   A 机：网页客户端(100)     │   │   B 机：网页客户端(101)      │
      │   WebClientServer : 8081   │   │   WebClientServer : 8081    │
      │   （SIP 引擎 5061/UDP）     │   │   （SIP 引擎 5061/UDP）      │
      └─────────────┬─────────────┘   └──────────┬─────────────────┘
                    │                            │
                    └──── 音视频 RTP 经服务器中继 ──┘
                         （服务器「转发媒体流」）

      A 机另设：
        · 后台管理端 AdminServer : 8090（读服务器 + 本机事件日志）
        · 会议视频中继 VideoRelay : 9100/UDP
          （miniSIPServer 会议室只混音，多方视频由此扇出）
```

**要点**

- **服务器只有一个**（装在 A 机）；两台电脑上都跑**各自的客户端**
- A、B 若**不在同一网段**，两端无法直连媒体，**必须由服务器中继 RTP**
  → 依赖分机的 **「转发媒体流」权限**（`setup-mss.ps1` 会自动配置）
- 后台管理端只需在一台机器上开；它的**通话/消息统计只反映本机事件**

### 3.1 部署参数（示例）

> ⚠️ 下表 IP 是**示例**，只为说明"每台电脑各有自己的 IP"。
> **仓库作者调试时用的是作者自己的 IP；你自行部署请全部换成你自己两台机器的 IP。**

| 项 | 值（占位/示例） |
|---|---|
| A 机（服务器 + 客户端 100 + 后台管理端） | `<A 机 IP>`，例 `192.168.1.10` |
| B 机（客户端 101） | `<B 机 IP>`，例 `192.168.1.20` |
| 服务器 SIP | A 机 `5060/UDP` |
| 服务器 Web 管理 | A 机 `8080/TCP`，口令 `1234` |
| 网页客户端 | 各机 `8081/TCP` |
| 后台管理端 | A 机 `8090/TCP`，口令 `1234` |
| 会议视频中继 | A 机 `9100/UDP`（`run-video-relay.bat`） |
| 分机号 | A = `100`，B = `101` |

> ⚠️ **IP 是每次开工最容易变的东西**：用 `ipconfig` / `Get-NetIPAddress` 核对，
> 并保证 `config.properties` 的 `sip.local.ip` 就是本机当前 IP。
> B 机的 `sip.server.ip` 与 `mss.web.host` 要填 **A 机的 IP**。

> 📌 **关于代码里的默认 IP**：`SipVideoCallClient` 中 `sip.server.ip` / `sip.local.ip` 的**兜底默认值**
> 是仓库作者本机的地址，**只有在缺少 `config.properties` 时才会用到**。
> 正常部署请先 `copy config.properties.example config.properties`，再填**你自己**的 IP
> （模板里用的是 `192.168.1.100` 这类通用示例）。

### 3.2 端口与防火墙

| 端口 | 协议 | 用途 | B 需要访问 A 吗 |
|---|---|---|---|
| 5060 | UDP | SIP 信令（注册/呼叫） | ✅ **必须** |
| 8080 | TCP | 服务器 Web 管理（B 读在线联系人） | ✅ **必须** |
| 5061 | UDP | 客户端 SIP 端口 | ❌ |
| 8000 / 8020 | UDP | 客户端音视频端口 | ❌（媒体由服务器中继） |
| 8081 | TCP | 网页客户端 | ❌（仅本机浏览器） |
| 8090 | TCP | 后台管理端 | ❌（仅本机浏览器） |

> 因为媒体走服务器中继，**B 只需要能访问 A 的 `5060/UDP` 与 `8080/TCP`**。
> 若 B 的左侧联系人列表为空，多半是 `8080` 不通，在 **A 机（管理员身份）** 放行：
> ```powershell
> New-NetFirewallRule -DisplayName "miniSIPServer Web 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
> ```

---

## 4. 功能实现现状

| 课程要求 | 状态 | 说明 |
|---|---|---|
| ① 音视频通话 | 🟢 **完成** | 语音/视频通话，双机实测通过（双向画面 + 声音） |
| ② 文字消息 | 🟢 **完成** | SIP MESSAGE |
| ② 图片消息 | 🟢 **完成** | 分片发送，接收端内联显示 |
| ② 语音消息 | 🟢 **完成** | 录音（可随时停止）→ 发送 → 对端可播放；发送方自己那条也能回放 |
| ② 视频消息 | 🟢 **完成** | 发送视频文件，接收端可播放 |
| 多人文字群聊 | 已实现，离线自动测试通过；三端人工 NOT_RUN | 独立群 ID、邀请确认、成员名单、应用回执、失败重试和群/私聊隔离；详见 [群文字说明](GROUP_TEXT_CHAT.md) |
| ③ 群聊（多方音视频） | 🟢 **完成** | 拨 **`*6*1`** 入会：音频由服务器会议室混音；视频经 `VideoRelay` 扇出，网页**多路并排显示** |
| ④ 后台管理与查询统计 | 🟢 **完成** | Web 仪表盘：分机在线状态、会议室、通话记录统计、消息统计 |

### 已交付的三种运行形态

| 形态 | 入口 | 说明 |
|---|---|---|
| **网页聊天客户端** | `run-web.bat` → http://localhost:8081/ | 气泡聊天、图片/语音/视频消息、语音/视频通话、联系人 |
| **后台管理端** | `run-admin.bat` → http://localhost:8090/ | 管理仪表盘（口令 `1234`） |
| 命令行客户端 | `run.bat` | 早期形态，支持群聊入会等；调试用 |

---

## 5. 目录结构与关键类

```
sipvideo/
├── pom.xml                     Maven 配置（JDK 8 + JAIN SIP + webcam-capture + Gson）
├── config.properties.example   配置模板（复制成 config.properties 使用）
├── run.bat                     一键启动：命令行客户端
├── run-web.bat                 一键启动：网页客户端
├── run-admin.bat               一键启动：后台管理端
├── setup-mss.ps1               一键配置 miniSIPServer（分机/会议室/触发/媒体中继权限）
├── README.md                   本文档
├── A机部署与验证清单.md          A 机（服务器 + 客户端100 + 后台）部署文档
├── B机部署与验证清单.md          B 机（客户端101）部署文档
├── C机部署与验证清单.md          C 机（客户端102）部署文档（三方群聊）
├── DEPLOY-2PC.md               早期命令行客户端的两机部署说明（历史文档）
└── src/main/
    ├── java/org/example/
    │   ├── SipVideoCallClient.java    ★ 核心：SIP 信令 + RTP 媒体 + 消息收发 + 事件日志
    │   ├── WebClientServer.java       ★ 网页客户端：HTTP 桥接 + SSE 推送
    │   ├── AdminServer.java           ★ 后台管理端：登录 + API + 仪表盘
    │   ├── MssClient.java             miniSIPServer Web 接口客户端（会话复用）
    │   ├── EventLog.java              事件日志（通话/消息，供后台统计）
    │   ├── AudioCapture.java          麦克风采集
    │   ├── VoiceIO.java               语音录制(WAV)/播放 + 可停止录音器
    │   ├── PCMUEncoder.java           PCM ⇄ G.711 μ-law 编解码
    │   ├── RTPAudioSender.java        RTP 音频打包发送
    │   ├── RTPAudioReceiver.java      RTP 音频接收
    │   ├── VideoCapture.java          摄像头采集（自动挑选可用分辨率）
    │   ├── VideoCodec.java            JPEG 编解码（ImageIO）
    │   ├── RtpVideoSender.java        MJPEG 分片打包（一帧多包）
    │   ├── RtpVideoReceiver.java      按偏移重组帧
    │   └── VideoWindow.java           命令行模式下的 Swing 双画面窗口
    └── resources/
        ├── chat.html                  网页聊天界面
        ├── dashboard.html             后台管理仪表盘
        ├── login.html                 后台登录页
        └── log4j.properties           日志配置
```

> 运行 / 构建时会自动生成以下文件（均已加入 `.gitignore`，可随时删除）：
> `sipDebug.txt`、`sipServer.txt`（SIP 协议栈日志，可能几十 MB）、
> `logs/`（后台统计数据源）、`received/`（收到的图片/语音/视频）、`target/`（构建产物）。

### 5.1 核心类职责

| 类 | 职责 |
|---|---|
| `SipVideoCallClient` | 唯一的 SIP 引擎：注册/呼叫/接听/挂断、音频与视频 RTP、文字与文件消息、对外暴露 `Ui` 回调供网页层订阅事件 |
| `WebClientServer` | 起 HTTP 服务，把浏览器操作转成对引擎的调用；用 SSE 把消息/文件/通话状态/视频帧推给网页 |
| `AdminServer` | 管理员口令登录；读 miniSIPServer 的在线分机与会议室；读 `logs/events.tsv` 汇总通话与消息统计 |
| `VideoRelay` | 会议视频中继：按房间把 JPEG 帧扇出给其他参与者（UDP 9100） |
| `ConferenceVideo` | 客户端会议视频：采集→编码→发中继；接收多人画面并回调给网页 |
| `MssClient` | 登录 miniSIPServer Web 接口并调用 `/dbajax/*`（**全局复用一个会话**，避免高频登录被拒） |

### 5.2 文件消息分片协议

图片 / 语音 / 视频消息通过 SIP `MESSAGE` 分片传输，帧格式：

```
#FILE|<类型>|<文件id>|<文件名Base64>|<片序号>|<总片数>|<Base64数据>
类型: IMG | VOICE | VIDEO
```

- 每片 **800 个 Base64 字符**：加上约 300 字节 SIP 头后仍不超过以太网 MTU，
  避免 UDP 分片被链路丢弃（实测 `Content-Length > 1000` 就会被丢）
- ⚠️ 这是**本项目自定义协议，只有本项目客户端之间互通**（手机端 Linphone 不认识）

---

## 6. 环境要求

- **JDK 8**
- **Maven 3.x**
- **miniSIPServer V70**（装在作为服务器的机器上）
- Windows（当前在 Windows 上开发验证）

---

## 7. 快速开始

> 详细步骤见 [A机部署与验证清单.md](A机部署与验证清单.md)、[B机部署与验证清单.md](B机部署与验证清单.md)
> 与 [C机部署与验证清单.md](C机部署与验证清单.md)（第三方，用于三方群聊验证）。

### 7.1 服务器（A 机）

1. 启动 `miniSIPServer`，Web 管理台 `http://127.0.0.1:8080/`，口令 `1234`
2. 一键配置分机、会议室、**「转发媒体流」权限**：

```powershell
powershell -ExecutionPolicy Bypass -File setup-mss.ps1 -ServerHost 127.0.0.1 -ServerPort 8080 -AdminPassword 1234 -ExtensionList 100,101,102 -RoomList 1
```

> ⚠️ **miniSIPServer 每次重启后都要重跑这个脚本**，否则分机与「转发媒体流」权限会丢，
> 表现为「没声音、没画面、只能单向看见」。

3. **多人视频才需要**：另开一个窗口双击 `run-video-relay.bat`，
   看到 `监听端口: UDP 9100` 即就绪（会议室本身的 SDP 只有音频，视频靠它扇出）。

### 7.2 客户端（A 机 / B 机各一份）

1. 复制配置模板并修改：
   ```powershell
   copy config.properties.example config.properties
   ```
   **模板里的 IP 是占位示例，必须换成你自己的**：
   - `sip.local.ip` = **本机当前 IP**（`ipconfig` 查），不能填 `127.0.0.1`
   - `sip.server.ip` = 跑 miniSIPServer 那台机的 IP
   - `sip.username` = 本机分机号；`sip.target.user` = 对端分机号
2. 双击 `run-web.bat`，等待出现 `✓ 注册成功!`
3. 浏览器打开 **http://localhost:8081/**

### 7.3 后台管理端（任选一台）

双击 `run-admin.bat` → 浏览器打开 **http://localhost:8090/** → 口令 `1234`

### 7.4 开工自检（每次开始前跑一遍）

```powershell
# 1) 本机 IP —— 要与 config.properties 的 sip.local.ip 一致
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '169.254*' -and $_.IPAddress -ne '127.0.0.1' } | Select-Object IPAddress,InterfaceAlias

# 2) 服务器端口是否在监听（在 A 机执行）
netstat -ano -p UDP | Select-String ":5060"     # SIP
netstat -ano -p TCP | Select-String ":8080"     # 服务器 Web

# 3) 与对端是否互通（把 IP 换成对方机器）
Test-Connection -ComputerName <B 机 IP> -Count 2
```

### 7.5 IP 变了要改哪里

| 谁的 IP 变了 | 要改的配置 |
|---|---|
| **A 机 IP 变** | A 的 `sip.local.ip`、`sip.server.ip`；**以及 B 的** `sip.server.ip`、`mss.web.host` |
| **B 机 IP 变** | 只需改 B 的 `sip.local.ip` |

> ⚠️ `sip.local.ip` 填错的表现：能注册、能接通，但**没声音、没画面**。

### 7.6 代码版本

两台机器都执行 `git pull`，并用 `git log --oneline -1` 确认两边是**同一个提交**。
（用了旧代码会出现：没有联系人、录音只能 5 秒、挂断后画面卡住等已修复的问题。）

---

## 8. 使用说明

### 8.1 网页聊天客户端（8081）

| 区域 | 功能 |
|---|---|
| 顶部 | `已注册` 状态、`我 xxx → 对端 xxx` |
| 左侧 | 联系人 / 在线状态（点击可切换对端） |
| 中部 | 消息气泡：文字、图片（内联）、语音（播放器）、视频（播放器）；通话时显示本地/对方画面 |
| 下部 | **拨号栏**：输入分机号后点「语音 / 视频」直接呼叫 |
| 底部 | 发送、图片、🎤录音、视频、**语音通话**、**视频通话**、挂断 |

**拨号**：在「拨号」栏输入号码，点「语音」或「视频」即可呼叫——
普通分机号（如 `101`）点对点通话；输入 `*6*1` 则**进入会议室 1**（多方）。
不填号码直接点「语音/视频通话」则呼叫左侧当前选中的对端。

**群聊（多方视频）**：A、B 各自在「拨号」栏输入 `*6*1` 点「语音」入会。
- **音频**：由 miniSIPServer 会议室混音
- **视频**：由 A 机的 `run-video-relay.bat` 中继扇出，网页把每位参与者画面**并排显示**（本地标「我」）
- ⚠️ A 机必须先启动 `run-video-relay.bat`，否则**只有声音没有画面**

**语音消息**：点「🎤 录音」开始（按钮变红并计时）→ 再点「⏹ 停止并发送」结束并发出。
⚠️ 录音用的是**运行该客户端那台电脑的麦克风**（不是浏览器麦克风）。

**通话音频**：同样走运行客户端那台电脑的麦克风/扬声器；网页只负责显示画面。

### 8.2 命令行客户端（run.bat）

| 输入 | 作用 |
|---|---|
| 直接输入文字 + 回车 | 发送文字消息 |
| `/img <图片路径>` | 发送图片 |
| `/video <视频路径>` | 发送视频消息 |
| `/rec [秒数]` | 录制语音消息并发送（默认 5 秒） |
| `/play` | 重放最近收到的语音消息 |
| `/quit` | 退出（或 `Ctrl+C`） |

> 群聊演示：**推荐用网页客户端**，在「拨号」栏输入 `*6*1` 点「语音」即可入会；
> 命令行客户端则可把 `sip.target.user=*6*1`（会议室 1）后启动。

### 8.3 后台管理端（8090）

- **分机与在线状态**：实时读 miniSIPServer（在线/振铃/通话中/离线、IP）
- **会议室**：服务器上的会议室列表
- **通话记录统计**：总次数、总时长、按对端排名、最近通话
- **消息统计**：按类型（文字/图片/语音/视频）的收发条数

数据来源：分机与会议室来自服务器；**通话/消息统计来自本机 `logs/events.tsv`**。

---

## 9. 配置项说明

配置文件 `config.properties`（不入库，模板见 `config.properties.example`）：

| 分类 | 关键项 | 说明 |
|---|---|---|
| 账号 | `sip.username` / `sip.password` | 本机分机号与密码 |
| 服务器 | `sip.server.ip` / `sip.server.port` | miniSIPServer 地址（默认 5060） |
| 本机 | `sip.local.ip` / `sip.local.port` | ⚠️ 必须填**本机真实 IP**，非 127.0.0.1 |
| 对端 | `sip.target.user` | 拨号/发消息的目标分机 |
| 媒体 | `media.local.port` / `video.local.port` | 音频/视频 RTP 端口 |
| 视频 | `video.enabled/width/height/fps/quality.percent` | 默认 320x240 @10fps，质量 50% |
| 消息 | `im.file.chunk.size` | 分片大小（默认 800，见 5.2） |
| 通话 | `call.duration.sec` | 到时自动挂断（默认 180 秒） |
| 网页 | `web.port` | 网页客户端端口（默认 8081） |
| 后台 | `admin.port` / `admin.password` | 后台端口与口令（默认 8090 / 1234） |
| 服务器接口 | `mss.web.host/port/password` | 供客户端与后台读在线状态、会议室 |

---

## 10. 已知限制

| 限制 | 说明 | 影响 |
|---|---|---|
| 自定义 `#FILE|` 协议 | 只有本项目客户端互通 | 与手机 Linphone 不能互发文件消息 |
| 视频为 MJPEG | 未用 H.264 | **视频仅本项目客户端之间互通**（音频 PCMU 是标准码，可与 Linphone 互通） |
| 后台统计范围 | 读本机 `logs/events.tsv` | 只反映运行后台那台机器的事件 |
| 大文件较慢 | 800 字符/片 + 30ms 限速 | 建议演示用小文件（视频 <1MB） |
| **服务器重启丢配置** | miniSIPServer 的特性 | 必须重跑 `setup-mss.ps1` |
| 跨网段依赖媒体中继 | 需「转发媒体流」权限 | 服务器未配置时没声音没画面 |
| 群聊视频需额外中继进程 | `VideoRelay`（UDP 9100）须在 A 机运行 | 未启动时群聊只有音频 |
| 群聊视频不经过 SIP/SDP | 由应用层中继扇出 | 手机 Linphone 无法加入该视频房间 |

---

## 11. 工程要点与踩坑记录

### 11.1 现象 → 处理（速查）

| 现象 | 根因 | 处理 |
|---|---|---|
| 通话**没声音 / 没画面 / 只能单向看见** | 分机缺**「转发媒体流」**权限，跨网段直连不通 | A 机跑 `setup-mss.ps1` |
| B 的**左侧联系人列表为空** | B 连不到 A 的 `8080`，或 B 用了旧代码 | 放行 TCP 8080（见 3.2 节）；两台都 `git pull` |
| **挂断后对方画面卡住** | 旧代码 `dialog` 跨通话未重置 | 两台都 `git pull` 到最新 |
| 新呼叫返回 **`486 Busy Here`** | 服务器有**僵尸通话**（客户端异常退出未挂断） | 重启 miniSIPServer，再跑 `setup-mss.ps1` |
| **注册失败** | 分机不存在 / 网络不通 | A 机跑 `setup-mss.ps1`；`ping` 服务器 |
| 服务器**重启后分机全没了** | miniSIPServer 配置会丢（正常现象） | 重跑 `setup-mss.ps1` |
| 后台页面报**「无法连接 miniSIPServer」** | `mss.web.host/port/password` 不对 | 核对 `config.properties` |
| 后台**统计一直是 0** | 本机还没有通话/消息事件 | 先做一次通话或发一条消息 |
| **中文乱码** | 终端 UTF-8 与 Java 默认 GBK 不一致 | 用 `run-web.bat` / `run.bat` 启动（已内置 UTF-8 参数） |

### 11.2 开发中的问题与解决

开发过程中遇到的问题与解决（供组员参考，也是报告素材）：

1. **UDP 分片被丢**：SIP MESSAGE 的 `Content-Length` 超过约 1000 就会被链路丢弃
   → 文件分片保守取 800 字符
2. **对称 RTP 校验丢包**：收发用不同端口时对端会丢包
   → **RTP 发送器复用接收器的同一个 socket**（收发同端口）
3. **协议栈日志爆炸**：`TRACE_LEVEL` 从 32 降到 16
4. **进程不退出导致端口占用**：JAIN SIP 的线程非守护线程
   → 显式 `System.exit(0)`
5. **SIP 注册是软状态**：服务器重启后绑定会丢
   → 客户端定时重注册（默认 300 秒）
6. **`dialog` 跨通话未重置**（关键 bug）：挂断时 `BYE` 会发给**上一次的对端**，
   表现为「对方收到挂断后画面卡住」
   → 通话结束清空 `dialog` / `inviteTransaction`
7. **`stopMedia()` 未置空媒体组件**：导致 `isMediaActive()` 恒为真，通话后无法录音
8. **miniSIPServer 会议室拨号 404**：会议室业务前缀 `*6*` 未注册到服务触发表
   → `setup-mss.ps1` 自动注册（`tbl_lua_trigger`）
9. **服务器重启丢配置**：分机/会议室/触发都会没
   → 一键脚本 `setup-mss.ps1`
10. **「转发媒体流」权限**（跨网段通话的关键）：
    用脚本重建分机时若不带 `right_relay`，服务器不再中继媒体，
    两台电脑不在同一网段时就「没声音、没画面、只能单向看见」
11. **高频登录被服务器拒绝**：读 Web 接口时每次请求都重新登录会被拒
    → 全局复用一个会话，失效才重登
12. **浏览器端口的编码问题**：终端 UTF-8 与 Java 默认 GBK 不一致导致中文乱码
    → 统一加 `-Dfile.encoding=UTF-8`
13. **会议视频挂断卡死**：`DatagramSocket.receive()` 阻塞期间一直持有 socket 锁，
    同一 socket 上再 `send()`（心跳/LEAVE）会永久等待 → **收发改用两个独立 socket**
14. **拨会议室触发两次建流**：服务器会回呼本机并再带一份 SDP，
    重复绑定媒体端口报 `BindException` → `startMedia` 检测到媒体已在运行就忽略

---

## 12. 进度与后续计划

**已完成**：环境搭建 → 服务器部署 → SIP 注册与通话 → 双向音频 → 文字/图片/语音/视频消息 → 多方音频会议 → 视频通话 → 网页客户端 → 后台管理端 → 双机实测 → 部署文档 → 网页端拨号入会（`*6*1`）→ **多方视频（会议视频中继）**

**待办**

- [ ] 小组实验报告 / 个人实践报告 / 汇报 PPT
- [ ] 验收前的完整双机联调与录屏留档

---

## 13. 文档索引

多人文字群聊的架构、限制、自动测试与三端验收：[GROUP_TEXT_CHAT.md](GROUP_TEXT_CHAT.md)。

文件接收安全、配额边界与独立验收说明：[FILE_RECEIVE_SECURITY.txt](FILE_RECEIVE_SECURITY.txt)。

| 文档 | 用途 |
|---|---|
| **README.md**（本文） | 项目总览、选型、架构、现状 |
| [A机部署与验证清单.md](A机部署与验证清单.md) | A 机：服务器 + 客户端 100 + 后台管理端 |
| [B机部署与验证清单.md](B机部署与验证清单.md) | B 机：客户端 101 |
| [C机部署与验证清单.md](C机部署与验证清单.md) | C 机：客户端 102，三方群聊验证 |
| [DEPLOY-2PC.md](DEPLOY-2PC.md) | 早期命令行客户端的两机部署（历史文档） |

---

## 给组员的说明

- 想**快速跑起来** → 看第 7 节「快速开始」
- 想**改代码** → 先看第 5 节的关键类职责，核心逻辑都在 `SipVideoCallClient.java`
- 想**了解为什么这么设计** → 看第 2 节（选型）与第 11 节（踩坑记录）
- 遇到**没声音/没画面** → 先跑 `setup-mss.ps1`（十有八九是「转发媒体流」权限没了）
