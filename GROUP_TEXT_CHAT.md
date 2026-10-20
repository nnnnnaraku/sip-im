# 多人文字群聊：实现边界与三端验收

本功能从 `main@1cc201761921ddb668f159741c87df5ace7d35b6` 独立开发，不包含音频 P0 或视频诊断分支的改动。群文字与拨号 `*6*1` 的音视频会议是两个独立功能。无需新增服务器、数据库、监听端口或修改 miniSIPServer。

## 使用方式

1. 三个客户端使用同一份本分支构建，各自沿用有效 SIP 账号、私有配置和 Web 端口。
2. 在网页左侧点击“创建文字群”，从已有联系人中选择另外 2～7 人，填写群名。
3. 创建者自动加入；受邀客户端出现同一 Group ID 的邀请，各自点击“加入”。“已加入”是群成员状态，不表示用户当前在线。
4. 群文字在独立面板发送，不切换私聊联系人。每条消息显示逐收件人的状态。
5. 收件人离线、请求失败或回执超时时可重试；成员信息不同步时点击“同步”。群主的同步会重发邀请/名单，成员同步向群主请求名单，加入/退出待确认时重试相应控制请求。
6. 普通成员退出后立即在本机禁发禁收，待群主确认名单更新；群主退出会解散本群。当前版本不支持退群后自行重新加入、追加成员或转让群主，可另建群。
7. 切回私聊后继续使用原文字、图片、语音、视频文件及呼叫入口。群面板只发送文字，不把音视频会议当文字群。

## 架构与接口

- `GroupMessage`：版本化 JSON 协议 DTO。
- `GroupProtocol`：严格 JSON、UTF-8 字节大小、字段类型、UUID、成员状态和 Unicode 边界。
- `Group`：私有进程内群模型；不直接序列化，避免把群令牌暴露给网页。
- `GroupService`：群主名单权威、邀请确认、直接分发、应用回执、去重、重试和独立历史。
- `GroupTransport`：只接收显式 recipient，不读取共享 `targetUser`。
- `GroupWebApi`：独立群 API、受限 JSON 请求与原子快照。
- `group-chat.js`：独立群列表/输入/历史和成员视图，私聊、文件、会议事件保持原路径。

SIP 使用 `application/vnd.sip-im.group+json`，包括 `INVITE / ACCEPT / STATE / TEXT / ACK / LEAVE / SYNC`。每个 envelope 都有 version、groupId、messageId、senderId、ownerId、revision、timestamp 和群级随机令牌。只有群主能更新名单；初次邀请需要网页使用者确认加入。发言只投递给已加入成员。

发送一次文字时先固定群和收件人快照，然后逐个创建 SIP MESSAGE，**不调用 setTargetUser**。SIP From 必须与 senderId 一致，群主、群令牌、成员资格、名单版本均须匹配。SIP 事务通过 applicationData 关联原投递，最终响应或超时仅回调一次。

SIP 2xx 只标记 `SIP_ACCEPTED`；接收者验证并保存消息后发出独立应用 ACK，才标记 `DELIVERED`。ACK 不表示用户已读。超时/失败逐成员显示；重试保留原 Message ID，只发给原目标中尚未确认且仍为活动成员者。相同 ID/内容重传不会重复显示，ID 相同但内容冲突会拒绝。

群主通过版本名单同步成员；旧版本不回滚当前状态，退出不能被旧名单复活。若文字遇到名单不一致，拒绝该次并请求同步，发送者在同步后重试。重复控制请求只重新 ACK，不重复修改成员或放大名单分发。所有传输、UI 回调均在群锁之外。

HTTP 与 SSE 的全量群快照同时包含 `snapshotEpoch` 和 `snapshotSequence`，在同一锁内生成，页面丢弃过期快照及退役进程的响应，避免刷新覆盖新的送达/离群状态。群令牌不进入 API、SSE 或页面。

| API | 请求 |
|---|---|
| `GET /api/groups` | 群/成员/历史/投递状态快照 |
| `POST /api/groups/create` | JSON `{name,members:[其他用户]}` |
| `POST /api/groups/join` | JSON `{groupId}` |
| `POST /api/groups/send` | JSON `{groupId,text}` |
| `POST /api/groups/leave` | JSON `{groupId}` |
| `POST /api/groups/sync` | JSON `{groupId}` |
| `POST /api/groups/retry` | JSON `{groupId,messageId}` |

POST 需要注册成功及 `application/json`，浏览器 Origin 必须与 Host 一致（如提供）。SSE 使用独立 `t=group-state`，不把协议或群文字送入旧 `msg/file` 路径。旧 API、媒体与文件协议保留。

## 明确限制

- 客户端进程内保存状态。刷新页面会恢复，重启 Java 会丢失本机群/历史；无离线存储、跨设备漫游和中央数据库。验收期间不要重启已入群进程。
- 每群 3～8 个初始成员，群名最多 48 UTF-8 字节，正文最多 400 UTF-8 字节，整个协议包最多 800 UTF-8 字节。大量引号等转义字符可能先达到包上限，页面会显示拒绝原因。本轮不为长群文字新增分片协议。
- 每进程最多 16 群、每群 256 条历史、1024 条发出操作及1024条接收控制记录。达到上限明确拒绝并建议新建群/在计划中重启重建，不静默清除去重记录。群组数量上限需重启重建。成员变更前预检整批分发容量，不会部分提交名单。
- 应用回执等待 15 秒，页面每 5 秒查询状态；迟到的有效 ACK 可以恢复为 DELIVERED。群主离线时无法完成成员变更，页面显示 JOINING/LEAVE_PENDING，不能假称所有端已同步。
- 依赖受信任 SIP 服务器与群成员。随机令牌不是端到端签名或加密，SIP From 校验不能防止控制服务器/成员的攻击者。Web 继承既有可信局域网部署边界，Origin 检查不是用户鉴权。SIP 日志可能含群令牌和认证信息，应私有保存并脱敏。
- 群消息暂未接入旧后台 TSV 消息统计。没有群文件、群主转让、动态加人、持久化或已读回执。
- 群状态事件使用独立单线程异步队列/全量快照，设计目标为课程小群；尚未做高负载和慢浏览器压测。
- 本分支未引入音频 P0：main 上原有音频风险并不因此解决。需要同时使用音频修复或诊断能力时，先审查合并，再全量回归，不能混用旧 JAR。

## 本地自动化验证（无真实服务）

使用 JDK 8、JUnit 4.13.2、Surefire 3.5.4；POM 测试配置与音频 P0 一致，便于后续合并。

```powershell
# 在独立worktree，确保本次进程JAVA_HOME为本机已有JDK8。
mvn test
mvn package
node --check src/main/resources/group-chat.js
node src/test/js/group-chat.test.js
git diff --check
```

本机默认 Maven 缓存缺插件，已从原成功缓存只读复制到本 worktree 的 ignored `target/group-maven-repository`，不修改其他缓存或全局设置。离线复验命令：

```powershell
mvn -o -gs target/group-settings.xml -s target/group-settings.xml "-Dmaven.repo.local=$PWD/target/group-maven-repository" package
```

`target/group-settings.xml` 为空设置文件，仅本地生成。不要将构建缓存或日志加入 Git。测试使用内存消息泵、fake HttpExchange 和 JAIN 消息/头工厂代理；不创建 SIP Stack、HTTP Server、socket 或音视频设备。Node 用例实际执行网页原内联脚本及独立群模块的假 DOM，覆盖私聊入口兼容与快照乱序；不能代替真实浏览器和跨电脑通信。

## A100/B101/C102 人工验收表

以下全部 **NOT_RUN**，须另行安排三端负责人。先用同一提交构建并核对 JAR/依赖哈希；每端独立有效私有配置、运行目录与端口。不要改账号密码/miniSIPServer/防火墙，不为群文字启动 VideoRelay。原有 `run-web.bat` 会重建并启动，不作为只读预检；真实启动须另行批准。

可先准备运行依赖（只构建，不启动）：

```powershell
mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/group-runtime-lib
```

获准后各端从各自独立运行目录、已有 JDK8 执行下列形态，配置在包外，不使用示例默认密码：

```powershell
$java='<已有JDK8>/bin/java.exe'
$project='<本分支构建目录>'
$config='<本人有效私有配置绝对路径>'
$cp="$project/target/sipvideo-1.0-SNAPSHOT.jar;$project/target/group-runtime-lib/*"
& $java '-Dfile.encoding=UTF-8' "-Dsip.config=$config" -cp $cp org.example.WebClientServer
```

这会自动 SIP REGISTER，本轮没有执行。群文字测试无需拨 `*6*1` 或启动任何中继。共享两台电脑运行三账号时，仍需隔离 SIP/RTP/P2P视频/Web 端口与运行目录；本次群文字不访问摄像头或麦克风。

| 场景 | 具体操作与通过标准 | 当前 |
|---|---|---|
| 创建与加入 | 100选择101/102建群；三端记录同一Group ID；101/102分别确认加入，三端名单均ACTIVE | NOT_RUN |
| 100发言 | 100发送唯一中文/英文/emoji/引号/换行文本；101与102均收到且仅一次，100两项应用回执DELIVERED | NOT_RUN |
| 101/102发言 | 各发送不同编号，另外两端均显示正确senderId与文本，六方向分别确认 | NOT_RUN |
| 多群隔离 | 同三人另建第二群，交替发唯一内容；每条只进入所属Group ID历史 | NOT_RUN |
| 私聊兼容 | 切回私聊，原文字/图片/语音/视频文件及呼叫接口按既有流程回归，不出现群协议原文 | NOT_RUN |
| 切联系人 | 群消息发送过程中切换私聊联系人，群收件人仍固定；不发给新选择的无关用户 | NOT_RUN |
| 退出 | 102退出并等群主确认；100/101发新消息不再投递102；102禁发；旧名单不复活 | NOT_RUN |
| 群主退出 | 100退出并解散，其他端收到关闭状态且不能继续发言，原私聊不受影响 | NOT_RUN |
| 重复与ACK丢失 | 使用获准的测试夹具重发同ID，不通过页面再次输入同文本冒充去重；一次显示、重ACK；重试失败成员不重发成功成员 | NOT_RUN |
| 离线/失败 | 一端正常停止或断开后发言，逐成员显示失败/超时而非全成功；恢复后按进程内状态限制重建或重试 | NOT_RUN |
| 群主离线 | 成员加入/离开待确认，界面禁发；群主恢复且原进程仍在时同步完成，不假称已全员更新 | NOT_RUN |
| 刷新与乱序 | 浏览器刷新后当前进程群/历史恢复；状态不会从DELIVERED/LEFT回退到过时值 | NOT_RUN |
| 无效身份 | 仅离线夹具构造非成员/错群/token/From/过大包，不对在线服务器注入未知异常包 | NOT_RUN |

每行记录 PASS/FAIL/BLOCKED/NOT_RUN、构建 SHA/JAR 哈希、实际 IP/端口、时间/时区、Group/Message/Sender ID、每端日志、回执及人工观察者。日志中的 token/Authorization 不公开分享。自动化成功、SIP 2xx、页面气泡出现都不能代替两端实际收到。

## 后续合并注意

主要冲突面是 `SipVideoCallClient.java` 的新增群路由/显式发送、`WebClientServer.java` 的路由/SSE、`chat.html` 的群界面接缝。应同时保留音频 P0 协商和视频诊断事件/元数据入口，不用整文件覆盖。POM 与 P0 相同；本分支不引入其余音频实现。合并后重新执行两边全部 Java/DOM 测试，再真实三端回归。
