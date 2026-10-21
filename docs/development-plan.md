# dailynotes 开发计划

> 配套文档：`app-design.md`（设计文档 v0.2，B 档架构：服务器为源 + 本地缓存 + LWW + 服务端快照）
> 节奏假设：AI 辅助开发 + 主人验收，每个里程碑结束可运行可演示

---

## 0. 里程碑总览（v0.2 B 档重排）

| 里程碑 | 内容 | 估时 | 产出 |
|---|---|---|---|
| M0 | 服务端起服务 + 协议联调 + 快照机制 | 0.5 天 | ✅ **已完成**（2026-10-21） |
| M1 | App 骨架 + 设置页 + SAF 缓存文件夹 | 1 天 | 四 tab 空壳 + 服务器可配 |
| M2 | `:sync` 模块 + 拉取链路 | 1-2 天 | App 里能看到服务器上的真实数据 |
| M3 | 主页信息流 + 编辑器 + 保存推送 | 2-3 天 | 需求 1/2/3/6 完成 |
| M4 | 笔记卡片流 + 删除 | 1-2 天 | 需求 5 完成 |
| M5 | 「我」+ 打磨 + 总验收 | 1-2 天 | MVP 全绿 |

总计约 **1-1.5 周**。B 档相对 v0.1（本地优先+状态机）省去离线队列/冲突合并/tombstone，且同步引擎提前（UI 依赖真实数据源）。

---

## M0：服务端 + 协议联调 + 快照 ✅ 已完成（2026-10-21）

**实测结论**（本机 docker，SFTPGo 2.7.6）：
- probe.sh 10/10 通过：etag 可靠（PROPFIND 列表稳定 + GET 带 ETag 头）→ **增量拉取成立**；条件 PUT 不支持 → B 档 LWW 无影响
- 部署坑固化进设计文档 §6.2：WebDAV 默认禁用（须 env）、admin env 不生效（用 loaddata.json）、REST API 不吃 basic auth
- **快照机制实测通过**：`backup.sh` 两份快照硬链接共享 inode（未变文件零空间）、保留 30 份自动旋转、恢复演练（改坏 → 从快照 cp 回旧版）通过

**产出**：`server/probe.sh`、`server/backup.sh`、`server/docker-compose.yml`、`server/loaddata.json`、`server/import.sh`、`server/export.sh`
**增补实测（2026-10-21）**：import.sh 存量导入 8 文件全成功（含附件月桶自动建目录）、二次导入全 SKIP（增量语义）、export.sh 全量导出与源数据 `diff -r` 一致——需求 11/12（导入导出 API + 全量缓存）的 API 层已闭环。
**剩余（部署时）**：VPS + 域名 + Caddy TLS 后重跑 probe.sh；cron 挂 backup.sh；Filebrowser 接入。

---

## M1：App 骨架 + 设置页 + SAF（1 天）

**任务**
1. Kotlin + Compose 项目初始化，四 tab 骨架（BottomNavigationBar + NavHost）
2. `:sync` 空模块占位（纯 Kotlin Gradle module）
3. SAF：`ACTION_OPEN_DOCUMENT_TREE` 选缓存文件夹 + `takePersistableUriPermission`，重启仍有效
4. 设置页完整版：服务器地址/账号/密码（EncryptedSharedPreferences）/根路径/缓存文件夹显示与更改/全量刷新/诊断
5. 「测试连接」按钮：MKCOL 三目录（diary/notes/attachments，幂等）

**验收**：设置全部可保存；重启后 SAF 权限与配置都在；点「测试连接」在服务器上建出三目录（ls 验证）。

---

## M2：`:sync` 模块 + 拉取链路（1-2 天）

**任务**
1. `SyncEngine` 接口 + `WebDavSyncEngine`（OkHttp 手写动词；PROPFIND XML 解析只取 href/getetag 两字段）
2. cache-state.json 读写（path→etag）
3. 拉取对账：三目录 PROPFIND → diff → GET 变化文件落缓存 → 更新清单
4. JVM 集成测试（§6 L1）：直连 sftpgo-probe 容器，覆盖空库/已有文件/etag 变化/文件新增/文件消失/断连
5. App 侧：启动 + 下拉刷新触发拉取；断网时缓存展示（标记「离线内容」）

**验收**：服务器手动放几个 md/图片（模拟 Filebrowser 端写入），App 打开能看到、能看到图片；改一个文件重新打开只重取那一个（logcat 验证 GET 数量）。

---

## M3：主页信息流 + 编辑器 + 保存推送（2-3 天）✅ 代码完成（2026-10-21，待装机验收）

已实现：`SyncCoordinator.push()`（LWW PUT 返回 etag）；`SafCache.list()/uri()`；`DiaryParser`（300 code points 截断 emoji 安全、图片折叠 [图]、首图提取、今天/昨天标签）+ 11 条单测；`EditorScreen/ViewModel`（photo picker 插图→attachments 月桶→相对引用；保存=写缓存→推未同步附件→推日记→更新 cache-state；失败仅提示）；`HomeScreen` 日记流（今天/同步按钮、倒序卡片、首图 Coil 缩略、点击编辑、空态）。
测试：:sync 10/10 + DiaryParser 11/11 全绿（JVM 直连容器）。按月分页未做（数据量小，v1 全量列表）。
坑：Coil 2.x 坐标是 `io.coil-kt` 非 `io.coil`；`Icons.Filled.Image` 不在 core 图标集（用 Add 替代，不引 extended 大依赖）。

---

## M4：笔记卡片流 + 删除（1-2 天）✅ 代码完成（2026-10-21，待装机验收）

已实现：`CacheSyncer` 统一同步门面（pull/push/delete，主页/笔记/编辑器三处共用，消除复制粘贴）；`SyncCoordinator.delete`（幂等）+ JVM 测试；`NoteParser`（`YYYYMMDDTHHMMSS-xxxx.md` 命名、300 字预览、首图）+ 5 测试；`NotesScreen`（卡片流、FAB「记一笔」、长按删除确认框）+ `NotesViewModel`（B 档删除：远端删成功才落本地，防幽灵复活）；`EditorScreen/VM` 通用化（relPath 参数，日记/笔记共用）；`CacheState` v2 加 `lastSync`（损坏自愈：parse 失败 → 空状态 → 下次全量拉回）。

## M5：「我」+ 打磨 + 总验收（1-2 天）✅ 代码完成（2026-10-21，仅剩真机验收）

已实现：`MeScreen`（统计：日记数/连续天数/笔记数/附件数/上次同步；导出 zip：CreateDocument + ZipOutputStream 递归三目录 + cache-state；关于）；`SafCache.listRecursive`；`DiaryParser.streak`（今天没写从昨天起算）+ 3 测试。
**测试终态：:sync 11 + DiaryParser 14 + NoteParser 5 = 30/30 全绿**（JVM 直连真实 SFTPGo 容器）。

### 三轮自审记录（2026-10-21，用户要求每阶段 ≥3 次）
- **R1 正确性**：[高] 编辑器 `relPath 相同跳过 load` 导致跨端编辑后显示旧内容 → 修：退出时 `discard()` 清状态（旋转仍保留未保存文本）；[中] OkHttpClient 每 CacheSyncer 新建积累线程池 → 修：companion 单例 + auth 挪到统一 request()；[中] 保存失败文案「已存本地缓存」误导（B 档下次同步会覆盖）→ 修：改为「内容仍在编辑器，可重试」
- **R2 边界故障**：断网/401/损坏 state/未选文件夹/Coil 解码失败/zip-slip 全部推演确认安全；[低] TextField+verticalScroll 手势冲突 → 移除
- **R3 终审**：streak 无测试 → 补 3 条；discard 首版调错 VM 对象（编译器抓出）→ 修为编辑器分支显式取同实例
- **遗留（真机验收清单）**：嵌套 Scaffold 的 FAB 偏移视觉问题；SAF 真机读写行为；PickVisualMedia 相册选图；Coil 加载 content uri；编辑器/列表导航手感

### 真机联调步骤（最后阶段执行）
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:18090 tcp:18090
# App 设置：http://localhost:18090 · probe/probepass123 · 根路径留空
```

---

---

## 6. 客户端-服务端联调方案（贯穿 M1-M5）

原则：**95% 调试在 JVM 层完成（不碰手机），真机只做验收**。

### L1 JVM 集成测试（主战场，M2）
- `:sync` 为纯 Kotlin Gradle 模块（OkHttp，零 Android 依赖），`SyncEngine` 实现直接 new 出来测
- 测试连本机 sftpgo-probe 容器（127.0.0.1:18090），覆盖：拉取对账/etag 变化/文件消失/断连超时/PUT 失败
- 每轮迭代秒级；同步逻辑 bug 全部在此层消灭

### L2 模拟器
- debug 构建挂 OkHttp LoggingInterceptor（release 关闭），Logcat 看完整请求/响应
- base url 用 `http://10.0.2.2:18090`（模拟器 → 宿主 loopback）
- 服务端侧 `docker logs -f sftpgo-probe` 对看双向事实

### L3 真机（USB）
- `adb reverse tcp:18090 tcp:18090`：手机 `localhost:18090` = 电脑 18090
- 与 compose 只绑 127.0.0.1 的安全设定天然配合，无需暴露局域网端口

### 对账工具
- `server/verify.sh`：服务端（数据卷）与客户端（adb pull 缓存目录）文件清单 + md5 对账，一键判定一致性

### 故障注入清单（负向用例）
| 场景 | 注入手段 | 期望 |
|---|---|---|
| 断网打开 | 飞行模式 | 缓存内容展示 + 「离线内容」标记 |
| 断网保存 | 飞行模式 / 关 WiFi | toast + 内容留编辑器，不写缓存 |
| 服务端僵死 | `docker pause sftpgo-probe` | 超时报错，不卡死 UI |
| 服务端宕机 | `docker stop` | 快速失败 + 重试按钮 |
| 别端编辑 | 手改数据卷文件（模拟 Filebrowser 端） | 下次打开拉取检测到 |
| LWW 覆盖找回 | 直改服务端旧版本 + 从快照 cp 回 | 快照恢复路径可用（M0 已演练） |

---

## 7. 测试策略

| 层 | 方式 |
|---|---|
| 纯逻辑单测（截断器/命名/对账 diff） | JUnit，必测：中文 emoji 截断、跨零点命名、etag 变化集计算 |
| WebDavSyncEngine | JVM 集成测试直连 sftpgo-probe 容器（M0 结论作断言基准） |
| UI | 手工为主（v1 不投入 UI 自动化） |
| 端到端 | 双设备手工清单（M4/M5 验收即清单） |

## 8. 仓库结构（M1 定型）

```
dailynotes/
  app/                      # Android (Kotlin/Compose)
    src/main/kotlin/.../
      ui/        (home|notes|editor|settings|me)
      domain/    (repo, model)
      store/     (saf 文件访问, cache-state)
  sync/                     # 纯 Kotlin 模块（零 Android 依赖，L1 联调的关键）
    src/main/kotlin/.../    (SyncEngine, WebDavSyncEngine)
    src/test/kotlin/.../    # JVM 集成测试，直连 sftpgo-probe 容器
  server/
    docker-compose.yml
    loaddata.json
    probe.sh
    backup.sh
    verify.sh               # M2 补
    Caddyfile               # 部署时
  docs/
    app-design.md
    development-plan.md
```

## 9. 风险与缓解

| 风险 | 概率 | 缓解 |
|---|---|---|
| LWW 静默覆盖（两设备离线同改同文件） | 低（条件苛刻） | 服务端每日快照可找回（已实测）；无法接受的场景退化 v0.1 A 档 |
| 服务器单点（磁盘坏/误删全量） | 低 | 每日快照（同盘）；异地备份列 v2（restic → 对象存储） |
| WebDAV XML 解析兼容性坑 | 中 | 只解析 getetag/href 两字段；probe.sh 固化服务端行为基准 |
| PROPFIND 大目录性能（几年后附件数千） | 低 | 按月分桶控制单目录规模；etag 对账只传元数据不传内容 |
| SAF 性能 | 低 | B 档下缓存访问点状（按需读写），无批量遍历场景 |
| 范围膨胀（富文本/AI/iOS 提前） | 高 | 非目标写死在设计 §2，新想法进 v2 backlog |

## 10. v2 路线（backlog，不做承诺）

- 异地备份（restic → 对象存储/另一台机器，防磁盘坏）
- 保存后台 WorkManager 自动重试（弱网体验）
- 可选 E2EE（每文件加密，替换 SyncEngine 实现即可）
- 日记搜索、日历视图；图片上传前压缩
- KMP 扩展 iOS/桌面；自定义极简 API 服务端（替换 WebDAV）

## 11. 里程碑放行规则

每个 M 结束：可运行 demo + 验收条目绿 + 主人过目后才进下一个 M。若 M2 拉取链路受阻（XML/etag 解析问题），回退方案：拉取降级为逐文件 HEAD/GET（牺牲增量，保正确性），增量后补。
