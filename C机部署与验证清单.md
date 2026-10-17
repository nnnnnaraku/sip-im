# C 机部署与验证工作清单

> 面向：C 机操作同学
> 目标：在 C 机上跑起**网页版聊天客户端**（分机号 **102**），与 A 机（**100**）、B 机（**101**）完成**三方群聊（多方音视频）**验证
> 本文件与《A 机部署与验证清单》《B 机部署与验证清单》配套，三份合起来 = 本次实验的完整部署文档
> 📖 项目总览 / 选型 / 架构 / 排障速查见 [README.md](README.md)

| 角色 | 机器 | IP | 分机 |
|---|---|---|---|
| SIP 服务器 + 客户端 100 | A 机 | `<A 机 IP>` | 100 |
| 客户端 | B 机 | `<B 机 IP>` | 101 |
| 客户端（本机） | C 机 | `<C 机 IP>` | 102 |

> ⚠️ **重要前提**：三台机器大概率不在同一网段，两两之间无法直连媒体，
> 必须由 A 机服务器的 **「转发媒体流」** 功能中转 RTP；会议视频还要经过 A 机的 **视频中继（UDP 9100）**。
> 因此：① C 的 `sip.local.ip` 必须填对；② 服务器分机 102 必须带「转发媒体流」权限（见第八节）。
> 服务器管理员口令：`1234`
>
> 📌 文中 `<A 机 IP>` / `<C 机 IP>` 是**占位符**，请替换成**你自己**机器的实际 IP
> （其中 `sip.server.ip`、`mss.web.host`、`video.relay.host` 填 **A 机的 IP**）。

---

## 一、前置条件

- [ ] C 机能 `ping <A 机 IP>` 通
- [ ] C 机已装 **JDK 8** 与 **Maven**
- [ ] C 机有可用的**摄像头与麦克风**（三方视频演示需要）

---

## 二、获取最新代码（必做）

在 C 机的项目目录执行：

```powershell
git clone <仓库地址>
```

或从 A 机整体拷贝 `sipvideo` 文件夹（**不要带 `target/`**）。

拉取后确认存在这些文件：

| 文件 | 作用 |
|---|---|
| `src/main/java/org/example/WebClientServer.java` | 网页客户端主程序 |
| `src/main/java/org/example/SipVideoCallClient.java` | SIP 信令 + 媒体 + 会议视频 |
| `src/main/java/org/example/ConferenceVideo.java` | 会议视频收发 |
| `src/main/java/org/example/MssClient.java` | 读服务器在线联系人 |
| `src/main/resources/chat.html` | 聊天界面 |
| `run-web.bat` | 一键启动网页客户端 |

> ⚠️ 跳过更新会用旧代码：会出现"左侧没有联系人""录音只能 5 秒""挂断后画面卡住""会议室没有画面"等已知问题。

---

## 三、配置 C 机的 `config.properties`

在 `sipvideo` 目录下新建 `config.properties`（可复制 `config.properties.example` 再改）：

```properties
# ---------- 本机账号（C 机 = 102）----------
sip.username=102
sip.password=102

# ---------- SIP 服务器（A 机）----------
sip.server.ip=<A 机 IP>
sip.server.port=5060

# ---------- 本机（C 机当前 IP，用 ipconfig 查）----------
sip.local.ip=<C 机 IP>
sip.local.port=5061
sip.register.interval.sec=300

# ---------- 对端（默认 A 机 = 100）----------
sip.target.user=100

# ---------- 媒体 ----------
media.local.port=8000

# ---------- 文件消息 ----------
im.file.chunk.size=800
im.voice.duration.sec=5

# ---------- 视频 ----------
video.enabled=true
video.width=320
video.height=240
video.fps=10
video.quality.percent=50
video.local.port=8020

# ---------- 通话行为 ----------
call.duration.sec=180
call.auto.invite=false

# ---------- 运行模式 ----------
app.keep.alive=true

# ---------- 网页客户端 ----------
web.port=8081

# ---------- 后台管理端（C 机不用开，留作备用）----------
admin.port=8090
admin.password=1234

# ---------- 读 miniSIPServer 的在线状态/会议室（填 A 机 IP）----------
mss.web.host=<A 机 IP>
mss.web.port=8080
mss.web.password=1234

# ---------- 会议视频中继（填 A 机 IP；留空则自动用 sip.server.ip）----------
video.relay.host=<A 机 IP>
video.relay.port=9100
```

**要点：**
- `sip.local.ip` 必须是 **C 机当前 IP**（用 `ipconfig` 核实），**不能填 127.0.0.1**
- `sip.username` / `sip.password` 必须是 **102**（102 也要在服务器上存在并有「转发媒体流」权限）
- `video.relay.host` 指向 **A 机**，端口 `9100/UDP`
- 端口 8000 / 8020 / 8081 在 C 机上是独立的，不会和 A、B 冲突，**不需要改**

---

## 四、检查 `run-web.bat` 的路径

打开 `run-web.bat`，看开头两行是否与 C 机实际安装位置一致：

```bat
if not defined JAVA_HOME  set "JAVA_HOME=D:\dev\jdk8u504-b01"
if not defined MAVEN_HOME set "MAVEN_HOME=D:\dev\apache-maven-3.9.16"
```

不一致就改成 C 机的路径（或把 JDK/Maven 加进系统 `PATH`）。

---

## 五、启动

- [ ] 双击 `run-web.bat`
- [ ] 等待出现 `✓ 注册成功!`（用户 102）和 `访问地址: http://localhost:8081/`
- [ ] 浏览器打开 **http://localhost:8081/**

> ⚠️ **不要同时**运行 `run.bat`（命令行客户端）—— 会和 `run-web.bat` 抢同一个分机 102。
> ⚠️ C 机**不需要**运行 `run-video-relay.bat`，视频中继只在 A 机运行一份。

---

## 六、功能验证清单（逐项打勾）

网页顶部应显示：`已注册`，`我 102 → 对端 100`。

| # | 功能 | 操作 | 预期 |
|---|---|---|---|
| 1 | 在线状态 | 看左侧"联系人" | 100、101 均绿点在线（若为空见第七节） |
| 2 | 文字（收） | 请 A 发一条文字 | C 窗口出现气泡，带分机号 100 |
| 3 | 文字（发） | C 输入文字→回车 | A 能收到 |
| 4 | 图片 | 点「图片」选图发送 | 对端气泡内直接显示图片 |
| 5 | 视频消息 | 点「视频」选小视频（<1MB）发 | 对端出现可播放视频 |
| 6 | 语音（录） | 点「🎤 录音」→说话→再点「⏹ 停止并发送」 | 对端可播放；**C 自己那条也能播放** |
| 7 | 语音通话 | 请 A 点「语音通话」 | 接听后**只有声音、无画面**；挂断后横幅消失 |
| 8 | 视频通话 | 请 A 点「视频通话」 | **双向画面 + 声音** |
| 9 | 挂断同步 | 由 A 点「挂断」 | **C 的画面立即消失**（无需 C 也按挂断） |
| 10 | **三方群聊（多方音视频）** | A、B、C 各自在「拨号」栏输入 `*6*1` 点「语音」入会 | 三端**声音互通**；每端网页**并排显示另外两人的画面**（A 机已启动 `run-video-relay.bat`） |
| 11 | 退出会议 | C 点「挂断」 | A、B 的画面中 **102 那一格立即消失**，其余两路不受影响 |

> 三方群聊的音视频路径：
> - **音频**：三端各自与 miniSIPServer 会议室（10004 端口）收发，由服务器**混音**
> - **视频**：三端把 JPEG 帧发给 A 机 `VideoRelay`（UDP 9100），由中继按房间**扇出**给其他两人

---

## 七、常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 左侧**没有联系人** | C 跨网段读不到 A 的 8080，或用了旧代码 | ① 确认已更新代码并重启；② 在 C 机执行 `Test-NetConnection <A 机 IP> -Port 8080`，**TcpTestSucceeded 为 False** 就让 A 机开防火墙 TCP 8080 |
| 通话**没声音、没画面**（或单向可见） | 分机 102 没有「转发媒体流」权限 → 服务器不中继；三机跨网段直连不通 | 在 **A 机** 跑 `setup-mss.ps1`（见第八节），这是**跨网段通话的关键** |
| **能听到声音、看不到画面** | A 机没启动视频中继，或 UDP 9100 被防火墙拦 | ① A 机确认 `run-video-relay.bat` 显示 `监听端口: UDP 9100`；② A 机中继日志应出现 `[加入] 102 -> 房间 1`；③ A 机放行 UDP 9100 |
| 三方画面里**只少自己** | 正常：中继不回发自己的帧，自己的画面由本地摄像头直接显示 | 无需处理 |
| 有声音**没画面**（点对点视频） | 摄像头被占用 / 选的是语音通话 | 关掉占用摄像头的程序（腾讯会议、相机等）；视频务必用「视频通话」按钮 |
| 三方**回声/重音** | 扬声器声音被麦克风再次采集 | 三端尽量**戴耳机**；或降低扬声器音量 |
| 注册失败 | 服务器上分机 102 不存在 / 网络不通 | A 机跑 `setup-mss.ps1` 重建分机；`ping <A 机 IP>` |
| C 机 IP 变化后失效 | `sip.local.ip` 没更新 | 改 `sip.local.ip` 为当前 IP 后重启 |
| 中文乱码 | 终端编码 | 用 `run-web.bat` 启动（已内置 UTF-8） |

---

## 八、服务器（A 机）重启后必做

miniSIPServer 重启会**丢失分机、会议室、服务触发、「转发媒体流」权限**。

在 **A 机** 的 `sipvideo` 目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File setup-mss.ps1 -ServerHost 127.0.0.1 -ServerPort 8080 -AdminPassword 1234 -ExtensionList 100,101,102 -RoomList 1
```

跑完后：
- [ ] 分机 **100 / 101 / 102** 都重新存在，且带**「转发媒体流」权限**（跨网段通话的前提）
- [ ] 会议室 1、服务触发 `*6*` 恢复

之后三台机器的客户端会**自动重新注册**（最长 5 分钟），或直接重启客户端立即生效。

---

## 九、交付

- [ ] A、B、C 三端网页客户端都能正常注册
- [ ] 第六节 11 项功能全部通过，重点是第 10 项三方群聊
- [ ] 三端同屏截图（每端都能看到另外两人画面）+ 录屏留档，用于验收答辩
