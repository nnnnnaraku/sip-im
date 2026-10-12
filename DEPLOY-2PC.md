# 双机部署指南（A 机 = 服务器，B 机 = 客户端）

本项目验收要求在 **两台电脑** 上演示。本文档说明第二台电脑（B 机）如何部署并接听视频通话。

> ⚠️ **本文档已过时（早期版本，仅作过程记录）**
> 它写于项目早期：当时用的是**命令行客户端**，并假设音视频 **RTP 点对点直连**（不经过服务器）。
> 现状：① 主推**网页客户端**；② 媒体**由 miniSIPServer 中继**（依赖分机「转发媒体流」权限），**不再直连**。
> 请以 [README.md](README.md)、[A机部署与验证清单.md](A机部署与验证清单.md)、[B机部署与验证清单.md](B机部署与验证清单.md) 为准。

---

## 1. 整体架构

只有 **一套服务器** + **两台客户端**，客户端程序完全相同（没有单独的"接收端"版本）。

```
             ┌────────────────────────────┐
             │   miniSIPServer（只装在 A 机） │
             │   注册 / 呼叫路由 / 信令    │
             └───────┬──────────┬─────────┘
              SIP    │          │    SIP
        （注册 / INVITE / 200 OK 等信令）
                     │          │
        ┌────────────▼──┐    ┌──▼────────────┐
        │   A 机 100    │    │   B 机 101    │
        │  SIP 客户端    │    │  SIP 客户端    │
        └───────┬───────┘    └───────┬───────┘
                └── 音视频 RTP（现由服务器中继）──┘
              （早期版本为点对点直连，现已改由 miniSIPServer 中转）
```

要点：

- **服务器**：`miniSIPServer`，**只装在 A 机**，B 机不需要安装。
- **客户端**：本项目（`sipvideo`），A、B **各一份**。
- **媒体流**：音频/视频 RTP。**当前由 miniSIPServer 中继**（依赖分机的「转发媒体流」权限）；早期版本为点对点直连。
- 被叫端收到 INVITE 后由代码自动接听（见 `SipVideoCallClient.handleIncomingInvite`），无需人工点确认。

---

## 2. 前置条件

| 项 | 要求 |
| --- | --- |
| 网络 | A、B 能互相 `ping` 通，且都能访问 A 机的 `miniSIPServer`（**不要求同一网段**；跨网段时媒体由服务器中继） |
| 服务器 | A 机已运行 `miniSIPServer`，且分机 **100、101 都已创建** |
| 软件 | B 机安装 **JDK 8** 与 **Maven**（或直接拷贝 A 机的 JDK / Maven 目录） |
| 硬件 | B 机有摄像头 / 麦克风；**没有摄像头也能用**（降级为"只收画面"，仍能看到对端） |
| 账号 | A 机用 `100`，B 机用 `101`，**不能重复** |

> 分机在 `miniSIPServer` 的创建方式：Web 管理界面手动加，或运行本项目的 `setup-mss.ps1`（幂等，可重复执行）。

---

## 3. B 机部署步骤

### 步骤 1：获取代码

```powershell
git clone <你的仓库地址>
cd sipvideo
```

或直接把 A 机的 `sipvideo` 文件夹整体拷贝到 B 机（**不要带 `target/`，编译产物没必要拷**）。

### 步骤 2：准备配置文件

```powershell
copy config.properties.example config.properties
```

（若直接跑 `run.bat`，它检测到没有 `config.properties` 会自动从模板复制并弹出记事本让你改。）

### 步骤 3：修改 `config.properties`

B 机需要改这几项（其余保持默认即可）：

```properties
# ---------- 本机账号（B 机 = 101）----------
sip.username=101
sip.password=101

# ---------- SIP 服务器（填 A 机 IP）----------
sip.server.ip=<A 机的局域网 IP>          # 例如 10.122.253.186
sip.server.port=5060

# ---------- 本机（填 B 机自己的 IP！）----------
sip.local.ip=<B 机的局域网 IP>           # 例如 10.122.253.200，绝不能用 127.0.0.1
sip.local.port=5061

# ---------- 被叫账号（A 机 = 100）----------
sip.target.user=100

# ---------- 关键：B 机只等接听，不主动拨号 ----------
call.auto.invite=false
```

> B 机的局域网 IP 用 `ipconfig` 查看（看"IPv4 地址"）。

### 步骤 4：启动

```powershell
run.bat
```

**注意**：`run.bat` 顶部写死了 JDK / Maven 路径（`D:\dev\jdk8u504-b01`、`D:\dev\apache-maven-3.9.16`）。如果 B 机的安装位置不同，请按实际情况修改这两行，或把 JDK / Maven 加进系统 `PATH`：

```bat
if not defined JAVA_HOME  set "JAVA_HOME=<B 机的 JDK 路径>"
if not defined MAVEN_HOME set "MAVEN_HOME=<B 机的 Maven 路径>"
```

启动后看到 `✓ 注册成功!` 和 `已关闭自动呼叫（call.auto.invite=false）`，说明 B 机已就绪，正在等待来电。

### 步骤 5：A 机发起呼叫

在 A 机的 `config.properties` 中把被叫设为 B 机：

```properties
sip.target.user=101
```

启动 A 机客户端。当 `call.auto.invite=true` 时，A 机启动后会自动向 `sip.target.user` 拨号 → 服务器路由到 B 机 → B 机自动接听 → 两端各弹出视频窗口，双向音视频建立。

> 说明：**命令行客户端**没有"运行时手动拨号"命令，A 机须在启动时自动呼叫（`call.auto.invite=true` 且 `sip.target.user` 指向 B 机）。
> **网页客户端**则可直接点按钮发起呼叫，无需自动拨号。

---

## 4. A 机 / B 机 配置对照

| 配置项 | A 机 | B 机 |
| --- | --- | --- |
| `sip.username` / `sip.password` | `100` / `100` | `101` / `101` |
| `sip.server.ip` | 服务器 IP（A 机自身） | **A 机 IP** |
| `sip.local.ip` | **A 机局域网 IP** | **B 机局域网 IP** |
| `sip.local.port` | `5061` | `5061`（不同机器同端口不冲突） |
| `media.local.port` | `8000` | `8000` |
| `video.local.port` | `8020` | `8020` |
| `sip.target.user` | `101` | `100` |
| `call.auto.invite` | `true`（主叫，自动拨号） | **`false`（被叫，等待接听）** |

> 只有当**同一台机器**上开两个客户端做联调时，才需要区分端口（如 `sip.local.port` 用 5061/5062、`media.local.port` 用 8000/8002、`video.local.port` 用 8020/8022），并用 `-Dsip.config=config-b.properties` 指定不同配置文件。**两台独立电脑不需要**这么做。

---

## 5. 端口一览

| 端口 | 用途 | 协议 |
| --- | --- | --- |
| 5060 | `miniSIPServer` SIP 信令 | UDP |
| 8080 | `miniSIPServer` Web 管理（读在线联系人） | TCP |
| 5061 | 客户端本地 SIP 端口 | UDP |
| 8000 / 8020 | 客户端音视频端口 | UDP |

> 媒体由服务器中继时，**B 只需要能访问 A 的 `5060/UDP` 与 `8080/TCP`**；
> 只有早期的"直连"模式才需要额外放行 8000/8020。

---

## 6. 验证清单

- [ ] B 机启动后打印 `✓ 注册成功!`
- [ ] B 机打印 `已关闭自动呼叫（call.auto.invite=false）`
- [ ] A 机拨号后，B 机打印 `[来电] 来自 ... ，自动接听...`
- [ ] B 机打印 `✓ 已接听，通话建立成功!`
- [ ] 两端各弹出视频窗口，能看到对方画面
- [ ] 双向语音正常（A 说话 B 能听到，B 说话 A 能听到）
- [ ] 音频电平监视显示收发均有数据包

---

## 7. 常见问题排查

| 现象 | 可能原因 | 解决 |
| --- | --- | --- |
| B 机注册失败 | `sip.server.ip` 填错 / 分机不存在 / 防火墙拦截 | 确认能 `ping` 通 A 机；在服务器确认 101 分机存在；放行 UDP 5060/5061 |
| 能拨号、B 机也接了，但**没声音/黑屏** | `sip.local.ip` 填成了 `127.0.0.1` 或错的网卡 IP | 改成 B 机真实局域网 IP |
| 信令通、媒体不通 | 分机缺「转发媒体流」权限，跨网段无法直连 | 跑 `setup-mss.ps1` 给分机加回「转发媒体流」权限 |
| 有声音没画面 / 画面卡 | 摄像头被其他程序占用 / 带宽不足 | 关闭占用摄像头的程序；调小 `video.quality.percent` 或 `video.fps` |
| 同一台机器开两个客户端时报端口占用 | 两个实例用了相同端口 | 分别用不同 `sip.local.port` / `media.local.port` / `video.local.port` |
| 中文乱码 | 终端编码问题 | 用 `run.bat` 启动（已内置 UTF-8 参数） |

---

## 8. 说明

- `config.properties` 与 `config-*.properties` 已加入 `.gitignore`，**每台机器各自维护**，不进仓库。
- 仓库内只保留 `config.properties.example` 作为模板。
- 视频方案为 **MJPEG over RTP**（PT 26，RFC 2435 简化版），编码/解码使用 JDK 自带 `ImageIO`，**定位为 Java 客户端之间互通**。
