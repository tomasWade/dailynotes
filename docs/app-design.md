# 日记 + 速记 App 设计文档

> 项目代号：`dailynotes`（暂名，可改）
> 版本：v0.1（2026-10-21 起草）
> 定位：自研、自托管、本地优先的 Android 日记/速记应用
> 前置结论：开源社区无成熟同类产品（调研见文末附录），故自研

---

## 1. 一句话定位

**UI 学 Memos（信息流 + 卡片），数据学 Obsidian（纯 Markdown 文件 + 附件目录），架构为「服务器为源 + 本地缓存」**（v0.2 定稿，B 档）：打开时从服务器拉取（本地缓存加速），点保存时推送到服务器，失败仅弹提示。

核心原则：
1. **服务器是唯一事实源**——App 是它的一个「视图 + 缓存」，所有端最终一致地指向服务器
2. **数据形态零锁定**——服务器上和缓存里都是纯 md 文件 + 附件，任何工具能直接读写
3. **没有同步状态机**——不做离线队列/冲突合并/tombstone：单人使用 + LWW（最后写入胜）+ 服务端定时快照兜底
4. **服务器端定时快照**——每日硬链接快照保留 30 份，误删/误改/LWW 丢写可一键回滚

---

## 2. 需求（用户原始 7 条 + 会话补充）

| # | 需求（原文要义） | 来源 |
|---|---|---|
| 1 | 主界面类似 Memos 信息流，但**每条 = 一天**，每天只有一条日记 | m0034 |
| 2 | 信息流每条只显示**前 300 字**，点进去编辑/查看全文 | m0034 |
| 3 | 支持**插入图片** | m0034 |
| 4 | 底部导航 4 个 tab：**主页 / 笔记 / 设置 / 我** | m0034 |
| 5 | 笔记 = 块状卡片流，**右下角加号**新建 | m0034 |
| 6 | 设置里配**服务器地址+端口**；点保存即同步；**失败仅弹提示**，不阻塞 | m0034 |
| 7 | 本地**数据文件夹可选**（SAF 文件管理器选目录，所有数据放里面） | m0036 |
| 8 | 纯文字为主偶尔贴图（数据形态 → Markdown 够用） | m0002 |
| 9 | 基本都有网，可接受「打开时拉取、保存时推送」的在线节奏 | m0002/m0034 |
| 10 | Web 端偶尔用（电脑上看/改） | m0001 |
| 11 | **数据导入/导出 API**：存量数据批量导入 + 全量导出 | m0113 |
| 12 | **本地存全量缓存**（总数据量不大，全量最简单） | m0113 |

**非目标（v1 明确不做）**：多人协作、实时协同编辑、端到端加密（v2 可选）、iOS/桌面客户端、AI 功能、富文本编辑（Markdown 即所见）。

---

## 3. 总体架构

```
┌─────────────── Android App（Kotlin + Compose）───────────────┐
│  UI 层：主页(日记信息流) / 笔记(卡片流) / 设置 / 我            │
│  领域层：DiaryRepo / NoteRepo / AttachmentRepo                │
│  缓存层：SAF 文件夹（服务器状态的本地下副本，含 md+附件）       │
│          cache-state.json（path→etag 表，判断缓存新旧）        │
│  同步层：SyncEngine 接口 ← WebDavSyncEngine（v1 唯一实现）     │
└────────────────────────────┬─────────────────────────────────┘
                             │ HTTPS + Basic Auth（WebDAV 动词）
┌────────────────────────────▼─────────────────────────────────┐
│  服务端（VPS 或家里 Linux，docker-compose）                    │
│  Caddy(TLS 反代) → SFTPGo(WebDAV) → 数据卷(唯一事实源)         │
│  backup.sh 每日快照（硬链接，保留30份）→ 防误删/误改/LWW丢写    │
│  Filebrowser 容器共享同一数据卷 → 电脑 Web 端看/改             │
└──────────────────────────────────────────────────────────────┘
```

---

## 4. 数据模型

### 4.1 目录布局（服务器为事实源，本地缓存同构）

```
服务器 home（事实源）／ 本地缓存文件夹（SAF 选中）—— 结构完全相同：
  diary/
    2026-10-21.md          # 每天一个，本地日期命名
    2026-10-20.md
  notes/
    20261021T083015-a1b2.md   # 速记卡片，本地时间 + 随机后缀
    20261019T221001-c3d4.md
  attachments/
    2026-10/
      20261021_083015_a1b2.jpg   # 图片按月分桶
  .dailynotes/
    cache-state.json       # 仅本地有：path→etag 缓存清单（可删除重建）
```

### 4.2 文件命名规则

| 类型 | 格式 | 理由 |
|---|---|---|
| 日记 | `diary/YYYY-MM-DD.md`（写入时的**本地日期**） | 人类可读、自然排序即时间序；「每天一条」由 App 保证（打开当天文件，没有则建） |
| 笔记 | `notes/YYYYMMDDTHHMMSS-xxxx.md`（本地时间 + 4 位随机 hex） | 可排序、可读、避免同秒碰撞 |
| 附件 | `attachments/YYYY-MM/时间戳_随机.jpg` | 按月分桶防单目录文件过多 |

### 4.3 文件内容规范

- **无 frontmatter、无私有扩展**：md 文件第一行就是正文
- 日记正文里引用图片用**相对路径**：`![](../attachments/2026-10/xxx.jpg)`（diary 与 notes 引用同一 attachments 目录）
- 笔记卡片文件全文即卡片内容，无标题行（渲染时按纯 md 段落处理）

### 4.4 本地缓存清单（cache-state.json，替代 v0.1 的 Room 索引）

> 服务器为源后不再需要数据库级索引：列表来自服务器，本地只需知道「缓存的新旧」。
> 一个 JSON 文件足够，删除后下次打开全量重拉即重建。

```json
{
  "version": 1,
  "files": {
    "diary/2026-10-21.md": { "etag": "\"18e07fb4...\"", "cachedAt": 1761024000 },
    "notes/20261021T083015-a1b2.md": { "etag": "\"a1b2c3...\"", "cachedAt": 1761024001 }
  }
}
```

用途仅两个：①打开时与 PROPFIND 的 etag 对账，变了才重新 GET；②渲染列表时优先读缓存、后台刷新。

---

## 5. 同步设计（v0.2 B 档：服务器为源 + LWW，无状态机）

### 5.1 模型：在线优先

| 时机 | 动作 |
|---|---|
| App 启动 / 下拉刷新 | **拉取**：PROPFIND 三个目录拿 etag 表 → 与 cache-state.json 对账 → etag 变了/没有的文件 GET，覆盖缓存 |
| 点保存（日记或笔记） | **推送**：附件先传 → md `PUT`（无条件，LWW）→ 成功才写缓存 + 更新 etag；失败弹 toast，内容留在编辑器 |
| 点删除 | `DELETE` 远端 → 删缓存 → toast；离线时删除按钮置灰 |
| 设置页「全量刷新」 | 清 cache-state.json → 重新对账（=重建缓存） |

**没有的东西**（v0.1 砍掉的，这是 B 档的简化本质）：离线写队列、冲突文件合并、tombstone、应用层乐观锁。代价与兜底见 §5.4。

### 5.2 SyncEngine 接口（领域层只认这个）

```kotlin
interface SyncEngine {
    suspend fun list(dir: String): Map<String, Etag>   // PROPFIND depth:1
    suspend fun get(path: String): ByteArray           // GET
    suspend fun put(path: String, data: ByteArray): Etag  // PUT（LWW，无条剑头）
    suspend fun delete(path: String)                   // DELETE
    suspend fun mkdir(path: String)                    // MKCOL
}
```

v1 唯一实现 `WebDavSyncEngine`（OkHttp 手写动词，纯 Kotlin 模块便于 JVM 层联调）。未来可加 `CustomApiSyncEngine` 不动上层。

### 5.3 WebDAV 映射

| 操作 | WebDAV 动词 | 备注 |
|---|---|---|
| 拉取列表 | `PROPFIND /diary/` depth 1，读 `getetag` | 一次拿全部 etag，与缓存对账 |
| 下载 | `GET` | etag 变化的文件 |
| 上传 | `PUT`（无条件） | LWW：最后写入胜 |
| 删除 | `DELETE` | |
| 建目录 | `MKCOL` | 首次连接初始化三个目录 |

> ✅ **M0 实测结论（2026-10-21，SFTPGo 2.7.6，本机 docker）**，详见 `server/probe.sh`：
> - **etag 可靠**：PROPFIND Depth 1 列表返回稳定 getetag，GET 响应头也带 ETag → 增量拉取（对账只重取变化文件）成立
> - **条件 PUT 不支持**：`If-Match` / `If-None-Match: *` 均被无视 → 反正 B 档是 LWW 无条件 PUT，此限制无影响
> - 起服务坑：镜像默认**不起 WebDAV**（须 env `SFTPGO_WEBDAVD__BINDINGS__0__PORT`）；默认 admin 环境变量不生效，用 `SFTPGO_LOADDATA_FROM` 挂 JSON 导入用户；REST API 不吃 basic auth（用 loaddata 绕过）

### 5.4 并发写与数据安全（LWW + 快照兜底）

**两设备并发写同一文件**（唯一需要面对的场景）：
- 后保存者覆盖先保存者，**先写版本不弹窗不提示地被覆盖**
- 触发条件苛刻：两台设备同时离线、各自改了同一天日记/同一条笔记、先后回网保存
- 兜底：**服务端每日快照**（§6.4）保留 30 天，被覆盖的版本可从快照恢复（恢复演练已通过）
- 用户权衡明示：接受极低概率的静默覆盖换架构极简；不接受则退回 v0.1 的 A 档（状态机），工程量 +50%

**删除**：点删除 = DELETE 远端 + 删缓存。误删同样从快照恢复。

**附件**：保存时解析 md 中 `attachments/` 引用，图片**先于 md 上传**；删除笔记不自动删附件（引用计数 v1 不做，附件留观）。

### 5.5 失败处理（需求 6 原样落实）

| 场景 | 行为 |
|---|---|
| 保存时网络失败/服务器挂 | toast「同步失败」；**内容留在编辑器**（不写缓存——服务器成功才算保存）；用户稍后重点保存 |
| 打开时网络失败 | 缓存里有 → 用缓存展示（标记「离线内容」）；缓存没有 → 报错页 + 重试按钮 |
| 保存成功但 etag 记录失败 | 无影响：下次打开对账会重新 GET 一遍，幂等 |

### 5.6 缓存策略：全量（需求 12）

本地缓存 = 服务器数据的**完整镜像**（diary/notes/attachments 全部文件）。首次配置后全量拉取，之后每次打开 etag 增量更新（只 GET 变化文件）。不做按需/分页缓存——数据量小（个人日记笔记），全量换简单：离线时所有内容可看，二次打开秒显。

### 5.7 数据导入/导出 API（需求 11，已实测 ✅）

**API 就是 WebDAV 本身**——标准 HTTP 动词，任何脚本/语言可直接操作数据，无需新增后端：

| 操作 | API |
|---|---|
| 写入/覆盖一个文件 | `PUT /diary/2020-01-05.md` |
| 读取 | `GET /diary/2020-01-05.md` |
| 列目录（含 etag） | `PROPFIND /diary/` `Depth: 1` |
| 删除 | `DELETE /diary/2020-01-05.md` |

配套工具（`server/` 下，2026-10-21 实测闭环）：
- **`import.sh <目录> [--force]`**：存量数据批量导入。增量语义：服务器已有文件跳过（防 LWW 覆盖别端改动），`--force` 全量重传；自动建目录含附件月桶。实测：8 文件首导全 201、二次全 SKIP
- **`export.sh <目录>`**：服务器全量镜像导出。实测：与源数据 `diff -r` 逐字节一致
- 这两个脚本同时是自定义集成脚本的写法范例（curl + PROPFIND/PUT/GET）

App 端的「导出 zip」（M5，「我」tab）复用同一数据层。

---

## 6. 服务端

### 6.1 选型：SFTPGo（WebDAV 服务）+ Caddy（TLS）+ Filebrowser（Web 端）

| 组件 | 作用 | 为什么 |
|---|---|---|
| SFTPGo | WebDAV 服务器 + 用户管理（自带 Web 管理界面） | 现成容器、服务端零代码、还附赠 SFTP/FTP 可选 |
| Caddy | HTTPS 自动证书（Let's Encrypt）反代 | 一行域名配置自动续期 |
| Filebrowser | 电脑上的 Web 端（需求 10） | 共享同一数据卷，支持 md 预览/编辑，零开发 |

### 6.2 docker-compose 骨架（M0 联调时校准参数）

```yaml
services:
  caddy:
    image: caddy:2-alpine
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
    depends_on: [sftpgo]
    restart: unless-stopped

  sftpgo:
    image: drakkan/sftpgo:latest
    environment:
      # 实测坑1：镜像默认不起 WebDAV，必须显式指定端口
      - SFTPGO_WEBDAVD__BINDINGS__0__PORT=8090
      # 实测坑2：默认 admin 环境变量不生效，启动时从 JSON 导入用户
      - SFTPGO_LOADDATA_FROM=/etc/sftpgo/loaddata.json
    volumes:
      - sftpgo_meta:/var/lib/sftpgo     # 用户/配额元数据
      - ./data:/srv/data                # 你的全部笔记数据（挂出来便于备份）
      - ./loaddata.json:/etc/sftpgo/loaddata.json:ro
    restart: unless-stopped

  filebrowser:
    image: filebrowser/filebrowser:latest
    ports: ["8080:80"]                  # 建议也走 caddy 反代 + 加 auth
    volumes:
      - ./data:/srv
    restart: unless-stopped

volumes:
  caddy_data:
  sftpgo_meta:
```

`loaddata.json`（部署时改密码；home_dir 指向数据卷内你的用户目录）：

```json
{
  "admins": [{"username":"admin","password":"改成强密码","status":1}],
  "users": [{"username":"haotian","password":"改成强密码","home_dir":"/srv/data/haotian","status":1,"permissions":{"/":["*"]}}]
}
```

```
# Caddyfile（域名换成自己的）
notes.example.com {
    reverse_proxy sftpgo:8090
}
files.example.com {
    reverse_proxy filebrowser:80
}
```

### 6.3 运维

- **资源**：三个轻容器，1C1G VPS 富余（SFTPGo 内存占用远低于 CouchDB 类数据库）
- **升级**：镜像是无状态的，数据卷在外，常规 `pull + up -d`

### 6.4 定时快照（LWW 的安全网，已实测 ✅）

`server/backup.sh`：rsync `--link-dest` 硬链接时间机器，cron 每日执行。

```
0 4 * * * /opt/dailynotes/server/backup.sh /opt/dailynotes/server/data /opt/dailynotes/server/snapshots 30
```

- **原理**：每日一份完整目录快照；未变文件硬链接共享 inode **零额外空间**，只有变化的文件占新空间
- **实测**（2026-10-21 本机）：两份快照中同一文件 inode 相同（41818837）确认共享；修改文件后次日快照仅该文件新块；恢复演练 = 从快照目录 `cp` 回数据卷，旧版本完整找回
- **保留**：默认 30 份，自动旋转
- **边界明示**：快照与数据同盘，防「人为错误」（误删/误改/LWW 覆盖）**不防磁盘损坏**；异地备份（restic → 对象存储/另一台机器）列 v2

### 6.5 部署位置

VPS（有公网 IP，随处可达，HTTPS 自然）或家里 Linux 机器（mac mini/nuc 均可，需要内网穿透或 Tailscale）。**推荐 VPS**。

---

## 7. 安全

| 层 | 方案 |
|---|---|
| 传输 | 强制 HTTPS（Caddy 自动证书），App 设置里只允许 https:// 前缀（http 仅允许局域网 IP 段调试用） |
| 认证 | SFTPGo 用户名 + 密码（Basic Auth），App 端存于 EncryptedSharedPreferences |
| 内容 | v1 不做应用层加密——VPS 被入侵可读到明文。**接受此风险**（日记非最高敏感级）；v2 可选每文件 age/AES 加密，接口已留 `SyncEngine` 抽象，替换实现即可 |
| Web 端 | Filebrowser 自带账号体系，不与 WebDAV 共享 |

---

## 8. Android 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言/框架 | Kotlin 2.x + Jetpack Compose | Android 优先的最正路线；SAF/相机/通知一等公民 |
| 文件访问 | SAF：`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` + DocumentFile | 需求 7 原生实现，重启后权限保留 |
| 图片加载 | Coil | Compose 原生支持，能直接吃 `Uri` |
| 网络 | OkHttp（`:sync` 纯 Kotlin 模块，手写 WebDAV 动词） | 零 Android 依赖，JVM 层直连容器秒级联调（开发计划 §6） |
| 缓存清单 | cache-state.json（文件存储） | 服务器为源后无需 Room，一个 JSON 足够，删了可重建 |
| 异步 | Coroutines + Flow | 标准 |
| 最低版本 | API 26（Android 8.0） | SAF 权限语义稳定，覆盖面足够 |

**为什么不用 Flutter**：SAF 深度集成、Compose 单目标 Android、避免 plugin 层不确定性；若未来要 iOS 再评估 KMP（领域层已是纯 Kotlin，可复用）。

**SAF 性能注意**：SAF 逐文件读写偏慢。B 档下 App 对缓存文件夹的访问更少（列表来自服务器 etag 表，文件按需读写），日记场景每天几次写、每次一个文件，完全无感。

---

## 9. UI 规格

### 9.1 主页（日记信息流）

- 顶部：当前日期 + 「今天的日记」快捷入口（浮顶层或列表第一项，进入当天编辑器，无则新建）
- 列表：倒序日期流，每条卡片 = 一天的日记
  - 头部：`2026-10-21 周三`
  - 预览：**前 300 个 Unicode code points**（`codePointCount`，正确处理中文/emoji），预处理：md 图片语法渲染为 `[图]` 标记、剥 md 标记保留文字、末尾加 `…`
  - 有图当天：卡片附首图缩略（Coil 加载）
  - 点击 → 全屏编辑器（可编辑、可插图、保存即同步）
- 下拉刷新 = 触发一次拉取
- 上滑无限加载（按月分页）

### 9.2 笔记（卡片流）

- 瀑布/单列卡片（v1 单列，简单），每卡全文渲染 md（短内容直接全显，超长同样 300 字截断 + 点开全屏）
- **右下角 FAB `+`** → 全屏新建编辑器 → 保存 = 写文件 + 推送
- 长按卡片：删除（DELETE 远端 + 删缓存，离线时置灰）/ 复制文本

### 9.3 编辑器（日记与笔记共用）

- 纯 Markdown 编辑（v1：单行/多行文本 + 工具栏插入语法；不做人富文本）
- 工具栏：图片按钮（相机/相册 → 复制进 `attachments/YYYY-MM/` → 插入相对路径引用）
- 保存按钮：写文件 → 同步 → toast 结果
- 日记编辑器顶部显示「这是 10 月 21 日的日记」，允许通过主页历史卡片编辑任意一天

### 9.4 设置

- 服务器地址（`https://host:port`，必填校验）
- 用户名 / 密码
- 远端根路径（默认 `/`）
- 缓存文件夹：显示当前 SAF 路径 + 「更改」按钮（重选后清缓存清单重新拉取）
- 按钮：全量刷新（清缓存清单重新对账）/ 导出数据（zip 到 SAF 目录）
- 诊断：上次拉取时间、上次保存结果、缓存文件数

### 9.5 我（用户未细说，v1 建议，标注待定）

- 统计：日记 N 天（连续 N 天）、笔记 N 条、附件 N 张 / 占用
- 备份导出入口（同设置里的导出）
- 关于 / 版本 / 开源许可

---

## 10. 边界情况

| 情况 | 处理 |
|---|---|
| 跨零点写日记 | 文件名按**创建那一刻**的本地日期；编辑器打开的是当天文件，零点后保存仍写原文件（当天=打开时决定的） |
| 旅行换时区 | 同上，本地日期为准，不做 UTC 归一（日记是给人看的，不是给机器） |
| 一个月没打开的手机 | 打开时 etag 对账，增量拉取，自动追平 |
| 两台设备同天各写了日记 | LWW：后保存者胜，先写版本被覆盖——从服务端快照（§6.4）可找回 |
| 断网打开 App | 缓存里有的内容照常展示（标记「离线内容」）；缓存没有的显示错误页 |
| 断网点保存 | toast 失败，内容留在编辑器不丢；恢复网络重点保存即推 |
| 图片被手动从服务器删了 | md 引用悬空 → 渲染为 `[图缺失]`，不崩溃不自动清理 |
| 换新手机 | 装 App → 设置里填服务器 → 选缓存文件夹 → 全量拉取，完成迁移 |
| 同一天重复打开 | 幂等：当天文件存在则直接编辑 |

---

## 11. MVP 验收标准（Definition of Done）

1. ✅ 主页信息流：每天一条、倒序、300 字截断（中文/emoji 不乱码）
2. ✅ 点任意一天可编辑可插图，图片在卡片上可见缩略
3. ✅ 四 tab 导航齐全，笔记 FAB 新建可用
4. ✅ 设置页可配服务器 + 账号 + 选缓存文件夹（SAF，重启后权限仍有效）
5. ✅ 点保存：服务器成功才算成功（curl PROPFIND 验证远端可见）；断网时 toast 且内容留在编辑器
6. ✅ 换设备：新装 App 配好服务器后全量拉回，数据一致
7. ✅ 删除笔记：另一设备打开 App 后该笔记也消失
8. ✅ 电脑浏览器通过 Filebrowser 能看到同样的文件并编辑
9. ✅ 服务端 `docker compose up -d` 一条命令起全套
10. ✅ 快照兜底：改坏一份日记 → `backup.sh` 的快照目录 cp 回旧版本（恢复演练 2026-10-21 已通过）
11. ✅ 数据导入：`import.sh` 批量导入存量数据，二次导入增量跳过（2026-10-21 已实测）；导出 `export.sh` 与服务器逐字节一致

---

## 附录 A：为什么不选现成方案（调研结论摘要）

| 方案 | 出局原因（均可溯源，详见调研记录 2026-10-21） |
|---|---|
| Memos（63,954★） | 单条信息流无法按日翻看（用户实测弃用）+ 无官方原生 App |
| Obsidian + Syncthing | 手机要装两个 App；vault 的手动管理 UX 被否 |
| Obsidian + Livesync | 同上，且 CouchDB 附件语义不适合当文件库 |
| Joplin / SiYuan / TriliumNext / Standard Notes / Notesnook | 各有同步付费墙 / 移动端弱 / 私有格式问题（详见 candidates 调研） |
| MoeMemos（907★） | 只是给 Memos 加原生客户端，没解决「按日聚合」的根本问题 |
| 社区同类自研 | GitHub 搜索 `self-hosted diary android` 前排全部 0-2★ 练手项目，无可复用成熟品 |

**结论：这个组合（按日聚合信息流 + 卡片速记 + 纯文件存储 + 自建同步 + 原生 Android）是市场空白，自研合理。**

## 附录 B：为什么事实源是文件而不是 SQLite

| 维度 | 纯 md 文件 | SQLite 单库 |
|---|---|---|
| 增量同步 | 改一天传一个 2KB 文件 | 改一个字整个库文件变，文件级同步无法 delta |
| 冲突粒度 | 不同文件天然无冲突 | 整库 LWW 必丢一边 |
| 零锁定 | 任何工具可读 | 需导出工具 |
| 图片附件 | 本来就是文件 | blob 入库则膨胀 |
| 查询 | 慢 → 列表由服务器 etag 表直接给出，无需全盘查询 | 快 |

B 档下连「派生索引」都不需要了：服务器 PROPFIND 一次给全量清单（etag 表），本地一个 JSON 记录缓存新旧即可。数据从头到尾只有 md 文件。
