# dailynotes

自托管、本地优先的 Android 日记 + 速记应用。**UI 学 Memos，数据学 Obsidian，同步走 WebDAV。**

- 主页：日记信息流，**每天一条**（`diary/2026-10-21.md`），300 字预览 + 首图
- 笔记：卡片速记流（`notes/时间戳-随机.md`），FAB「记一笔」，长按删除
- 我：统计 / 连续天数 / 全量导出 zip
- 数据 = 你选定文件夹里的**纯 Markdown 文件与图片**，任何工具可直接打开，零锁定
- 同步架构（B 档）：**服务器为唯一事实源**（SFTPGo/WebDAV）+ 本地全量缓存（SAF 可见）+ LWW + etag 对账 + 服务端每日快照兜底

设计文档见 `docs/app-design.md`，开发计划见 `docs/development-plan.md`。

## 服务端部署

```bash
cd server
# 改 loaddata.json 里的 admin/用户密码，home_dir 指向数据卷内用户目录
docker compose up -d          # SFTPGo :8080(管理) :8090(WebDAV)，默认只绑 127.0.0.1
./probe.sh                    # 协议语义自检（etag/条件PUT/增删改）
```

生产部署（VPS + 域名 + HTTPS）：参照 `docs/app-design.md` §6.2，前面加 Caddy 反代。

每日快照（防手滑/覆盖，保留 30 份）：

```bash
# crontab -e
0 4 * * * /path/to/server/backup.sh /path/to/data /path/to/snapshots 30
```

数据导入/导出：

```bash
server/import.sh <本地目录>          # 增量导入（已存在跳过，--force 全传）
server/export.sh <目标目录>          # 全量镜像导出
```

## Android App

```bash
export ANDROID_HOME=$HOME/Android/Sdk
gradle :app:assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
gradle :sync:test :app:testDebugUnitTest   # JVM 测试（sync 测试需本地 sftpgo 容器在跑）
```

真机联调：

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:18090 tcp:18090     # 手机 localhost:18090 → 电脑的 sftpgo
```

App 内「设置」：服务器 `http://localhost:18090`（生产换 `https://你的域名`）、账号密码、根路径留空、选择缓存文件夹（SAF）。

## 测试与验收

- `:sync` 11 条：直连真实 SFTPGo 容器（拉取/对账/删除/推送/etag）
- `DiaryParser` 14 条 + `NoteParser` 5 条：截断（emoji 安全）/命名/连续天数
- 真机验收清单与故障注入用例：`docs/development-plan.md` §6
