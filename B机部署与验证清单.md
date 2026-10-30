# B 机部署与验证工作清单

> 面向：B 机操作同学
> 目标：在 B 机上跑起**网页版聊天客户端**（分机号 **101**），与 A 机（分机 **100**）完成全部功能验证
> 📖 项目总览 / 选型 / 架构 / 排障速查见 [README.md](README.md)

| 角色 | 机器 | IP | 分机 |
|---|---|---|---|
| 服务器（miniSIPServer） | A 机 | `<A 机 IP>` | 100 |
| 客户端 | B 机（本机） | `<B 机 IP>` | 101 |

> ⚠️ **重要前提**：若 A、B **不在同一网段**，两端无法直连媒体，
> 必须由服务器的 **「转发媒体流」** 功能中转 RTP。
> 因此：① B 的 `sip.local.ip` 必须填对；② 服务器分机必须带「转发媒体流」权限（见第八节）。
> 服务器管理员口令：`1234`
>
> 📌 文中 `<A 机 IP>` / `<B 机 IP>` 是**占位符**，请替换成**你自己**两台机器的实际 IP
> （其中 `sip.server.ip` 与 `mss.web.host` 填 **A 机的 IP**）。

---

## 一、前置条件

- [ ] B 机能 `ping <A 机 IP>` 通
- [ ] B 机已装 **JDK 8** 与 **Maven**

---

## 二、获取最新代码（必做）

在 B 机的项目目录执行：

```powershell
git pull
git log -1 --oneline
```

若 B 机不是用 git 拉取的，请**整体重新拷贝 `sipvideo` 文件夹**（不要带 `target/`）。

- [ ] 版本为 `4932256` 或更新（含群聊 MIME 修复）；**三端必须同一版本**，否则群聊消息互相不认识

拉取后确认存在这些文件：

| 文件 | 作用 |
|---|---|
| `src/main/java/org/example/WebClientServer.java` | 网页客户端主程序 |
| `src/main/java/org/example/MssClient.java` | 读服务器在线联系人 |
| `src/main/java/org/example/EventLog.java` | 事件日志 |
| `src/main/resources/chat.html` | 聊天界面 |
| `run-web.bat` | 一键启动网页客户端 |
| `setup-mss.ps1` | 一键恢复服务器配置（**服务器重启后必跑**）|

> ⚠️ 跳过 `git pull` 会用旧代码：会出现"左侧没有联系人""录音只能 5 秒""挂断后画面卡住"等已知问题。

---

## 三、配置 B 机的 `config.properties`

在 `sipvideo` 目录下，确认（或新建）`config.properties`：

```properties
# ---------- 本机账号（B 机 = 101）----------
sip.username=101
sip.password=101

# ---------- SIP 服务器（A 机）----------
sip.server.ip=<A 机 IP>
sip.server.port=5060

# ---------- 本机（B 机当前 IP，用 ipconfig 查）----------
sip.local.ip=<B 机 IP>
sip.local.port=5061
sip.register.interval.sec=300

# ---------- 对端（A 机 = 100）----------
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

# ---------- 后台管理端（B 机不用开，留作备用）----------
admin.port=8090
admin.password=1234

# ---------- 读 miniSIPServer 的在线状态/会议室（填 A 机 IP）----------
mss.web.host=<A 机 IP>
mss.web.port=8080
mss.web.password=1234
```

**要点：**
- `sip.local.ip` 必须是 **B 机当前 IP**（用 `ipconfig` 核实），**不能填 127.0.0.1**
- B 机 IP 一旦变化，这一行必须同步改，否则没声音/没画面
- `call.auto.invite=false`：B 是被叫，不主动拨号

---

## 四、检查 `run-web.bat` 的路径

打开 `run-web.bat`，看开头两行是否与 B 机实际安装位置一致：

```bat
if not defined JAVA_HOME  set "JAVA_HOME=D:\dev\jdk8u504-b01"
if not defined MAVEN_HOME set "MAVEN_HOME=D:\dev\apache-maven-3.9.16"
```

不一致就改成 B 机的路径（或把 JDK/Maven 加进系统 `PATH`）。

---

## 五、启动

- [ ] 双击 `run-web.bat`
- [ ] 等待出现 `✓ 注册成功!`（用户 101）和 `访问地址: http://localhost:8081/`
- [ ] 浏览器打开 **http://localhost:8081/**

> ⚠️ **不要同时**运行 `run.bat`（命令行客户端）—— 会和 `run-web.bat` 抢同一个分机 101。

---

## 六、功能验证清单（逐项打勾）

网页顶部应显示：`已注册`，`我 101 → 对端 100`。

| # | 功能 | 操作 | 预期 |
|---|---|---|---|
| 1 | 在线状态 | 看左侧"联系人" | 100 绿点在线（若为空见第七节） |
| 2 | 文字（收） | 请 A 发一条文字 | B 窗口出现气泡，带分机号 100 |
| 3 | 文字（发） | B 输入文字→回车 | A 能收到 |
| 4 | 图片 | 点「图片」选图发送 | 对端气泡内直接显示图片 |
| 5 | 视频消息 | 点「视频」选小视频（<1MB）发 | 对端出现可播放视频 |
| 6 | 语音（录） | 点「🎤 录音」→说话→再点「⏹ 停止并发送」 | 对端可播放；**B 自己那条也能播放** |
| 7 | 语音通话 | 请 A 点「语音通话」 | 接听后**只有声音、无画面**；挂断后横幅消失 |
| 8 | 视频通话 | 请 A 点「视频通话」 | **双向画面 + 声音** |
| 9 | 挂断同步 | 由 A 点「挂断」 | **B 的画面立即消失**（无需 B 也按挂断） |
| 10 | 文字群聊 | A 建群后，在群聊面板点「加入」，再发一条群消息 | 群名与成员正确；A、C 看到同一条，不串到私聊 |
| 11 | 群聊（多方音视频） | 在「拨号」栏输入 `*6*1` 点「语音」 | 与 A 一起入会；音频互通；网页**并排显示多人画面**（需 A 机已启动 `run-video-relay.bat`） |

---

## 七、常见问题排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 左侧**没有联系人** | B 跨网段读不到 A 的 8080，或用了旧代码 | ① 先确认已 `git pull` 并重启；② 在 B 机执行 `Test-NetConnection <A 机 IP> -Port 8080`，**TcpTestSucceeded 为 False** 就让 A 机开防火墙 TCP 8080 |
| 通话**没声音、没画面**（或单向可见） | 分机没有「转发媒体流」权限 → 服务器不中继；而 A/B 跨网段直连不通 | 在 **A 机** 跑 `setup-mss.ps1`（见第八节），这是**跨网段通话的关键** |
| 有声音**没画面** | 摄像头被占用 / 选的是语音通话 | 关掉占用摄像头的程序；视频务必用「视频通话」按钮 |
| 注册失败 | 服务器上分机 101 不存在 / 网络不通 | A 机跑 `setup-mss.ps1` 重建分机；`ping <A 机 IP>` |
| B 机 IP 变化后失效 | `sip.local.ip` 没更新 | 改 `sip.local.ip` 为当前 IP 后重启 |
| 建群后**收不到邀请** | 旧版本群信封用了自定义 MIME，服务器回 `406` | 更新到 `4932256` 并重启客户端 |
| 群消息显示成**普通文字** | 旧版本无法识别群信封（服务器会剥离标记头） | 三端统一到 `4932256` |
| 群里发消息提示**未送达** | 自己还处于 `INVITED`，没点「加入」 | 先在群聊面板点「加入」 |
| 中文乱码 | 终端编码 | 用 `run-web.bat` 启动（已内置 UTF-8） |

---

## 八、服务器（A 机）重启后必做

miniSIPServer 重启会**丢失分机、会议室、服务触发、「转发媒体流」权限**。

在 **A 机** 的 `sipvideo` 目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File setup-mss.ps1 -ServerHost 127.0.0.1 -ServerPort 8080 -AdminPassword 1234 -ExtensionList 100,101,102 -RoomList 1
```

跑完后：
- [ ] 分机 100 / 101 重新存在，且带**「转发媒体流」权限**（跨网段通话的前提）
- [ ] 会议室 1、服务触发 `*6*` 恢复

之后 A、B 各自的客户端会**自动重新注册**（最长 5 分钟），或直接重启客户端立即生效。

---

## 九、交付

- [ ] A、B 两端网页客户端都能正常注册
- [ ] 第六节 9 项功能全部通过
- [ ] 截图/录屏留档，用于验收答辩
