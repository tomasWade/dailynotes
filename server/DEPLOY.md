# dailynotes 服务端部署（任意 Debian/Ubuntu Linux）

> 通用部署指南。架构：SFTPGo 容器（WebDAV）+ 每日快照备份，手机经局域网或 [Tailscale](https://tailscale.com) 访问。
> 推荐用 Tailscale：WireGuard 隧道加密，HTTP 无需再上 TLS/域名/证书（等效专线）。
> 所有 `<尖括号>` 是占位符，执行时替换。

---

## 0. 前提检查

```bash
cat /etc/debian_version          # Debian；Ubuntu 同样适用
docker --version && docker compose version
# 若走 Tailscale（推荐）：
tailscale ip -1                  # 记下本机 Tailscale IP，记为 <server-ip>
```

缺什么装什么：`sudo apt install docker.io docker-compose-plugin`；Tailscale 官方脚本安装并在手机上登录同一账号。

## 1. 拷贝 server/ 目录到目标机

```bash
# 从本仓库签出/下载后，把 server/ 目录拷到目标机，例如：
rsync -av \
  --exclude probe-files --exclude sftpgo-data --exclude snapshots \
  server/ <user>@<server-ip>:~/dailynotes-server/
```

## 2. 建用户（编辑 loaddata.json）

```bash
cd ~/dailynotes-server
nano loaddata.json
```

整份替换（**两个密码都换成强密码**，可 `openssl rand -base64 18` 生成）：

```json
{
  "admins": [{"username":"admin","password":"<管理界面密码>","status":1}],
  "users": [{"username":"<你的用户名>","password":"<App登录密码>","status":1,"home_dir":"/srv/notes","permissions":{"/":["*"]}}]
}
```

> loaddata 只在**首次建号**时生效；以后改密码走 web 管理界面（见 §8）。

## 3. 替换 docker-compose.yml 为正式版

```yaml
services:
  sftpgo:
    image: drakkan/sftpgo:latest   # 官方镜像多架构，ARM/Intel 均可
    container_name: sftpgo
    environment:
      # 坑1: 镜像默认不起 WebDAV，必须显式指定端口
      - SFTPGO_WEBDAVD__BINDINGS__0__PORT=8090
      # 坑2: 默认 admin 环境变量不生效，从 JSON 导入用户
      - SFTPGO_LOADDATA_FROM=/etc/sftpgo/loaddata.json
    ports:
      - "18080:8080"   # web 管理界面
      - "18090:8090"   # WebDAV（App 填 http://<server-ip>:18090）
    volumes:
      - ./sftpgo-data:/var/lib/sftpgo     # 用户/配额元数据
      - ./data:/srv/notes                 # 全部笔记数据（快照就备它）
      - ./loaddata.json:/etc/sftpgo/loaddata.json:ro
    restart: unless-stopped
```

## 4. 起服务 + 验证

**先建 sftpgo-data 目录（重要）**：`docker compose up` 会自动创建缺失的挂载目录，但那是 **root 属主**，容器内 sftpgo 用户（UID 1000）不可写 → sqlite 建库失败 → 容器陷入崩溃重启循环（日志特征：`unable to open database file`）。必须先手动建：

```bash
mkdir -p ~/dailynotes-server/{sftpgo-data,data}
```

```bash
cd ~/dailynotes-server
docker compose up -d
sleep 5
docker ps --filter name=sftpgo --format "{{.Status}}"   # 应为 Up；Restarting 见上面属主坑
docker logs sftpgo 2>&1 | grep -c "WebDAV server not started" || true  # 0 = WebDAV 正常

# 协议语义验证（7 项应全 PASS；T6-T8 是条件 PUT 探测不计分，SFTPGo 无视条件头属已知预期）
BASE=http://127.0.0.1:18090 AUTH='<你的用户名>:<App登录密码>' ./probe.sh
```

**拉不动镜像时**（部分网络环境）：用镜像源前缀拉取再改 tag：

```bash
docker pull docker.1ms.run/drakkan/sftpgo:latest
docker tag docker.1ms.run/drakkan/sftpgo:latest drakkan/sftpgo:latest
docker compose up -d
```

## 5. 挂每日快照 cron（正式数据的生命线）

```bash
crontab -e    # 加一行（<user> 换登录用户名，路径按实际调整）：
```

```
0 4 * * * /home/<user>/dailynotes-server/backup.sh /home/<user>/dailynotes-server/data /home/<user>/dailynotes-server/snapshots 30 >> /home/<user>/dailynotes-server/backup.log 2>&1
```

每天 04:00 快照，保留 30 份（rsync 硬链接时间机器，增量空间）。手动验证一次：

```bash
~/dailynotes-server/backup.sh ~/dailynotes-server/data ~/dailynotes-server/snapshots 30
ls ~/dailynotes-server/snapshots/
```

## 6. 手机 App 配置

设置里填：服务器 `http://<server-ip>:18090`、用户名/密码同 §2，根路径留空，选缓存文件夹，测试连接。

> 注意首次连接的「删除传播」语义：App 会把远端没有、本地缓存里有的旧文件清掉（对账跟随）。换新服务器后本地旧缓存会被清空，属预期行为。

## 7. 服务端更新（两类，别搞混）

**A. 上游镜像更新**（SFTPGo 新版/安全补丁）：容器无状态，随便升

```bash
cd ~/dailynotes-server && docker compose pull && docker compose up -d
```

**B. 项目文件更新**（脚本/compose 改了）——开发侧 rsync 推送：

```bash
rsync -av \
  --exclude probe-files --exclude sftpgo-data --exclude snapshots --exclude data \
  --exclude loaddata.json --exclude backup.log --exclude keystore-backup \
  server/ <user>@<server-ip>:~/dailynotes-server/
```

排除项的含义（**搞错会出事**）：
- `loaddata.json`：手填的密码，覆盖即丢失（密码管理走 web 界面）
- `data / sftpgo-data / snapshots`：业务数据与快照
- 推完后：只有 `.sh` 变了 → 无需操作；`docker-compose.yml` 变了 → `docker compose up -d`

## 8. 日常运维速查

```bash
# 改密码 / 建用户 / 看占用：web 管理界面 http://<server-ip>:18080（admin / <管理界面密码>）
#   Users → 你的用户 → Edit → Password（改完手机 App 设置同步更新）

# 手动快照（大事前先拍一份，如导入前）
~/dailynotes-server/backup.sh ~/dailynotes-server/data ~/dailynotes-server/snapshots 30

# 恢复某个文件（从快照拷回）
cp ~/dailynotes-server/snapshots/<时间戳>/diary/<日期>.md ~/dailynotes-server/data/diary/

# 全量导出（换机/双保险）
BASE=http://127.0.0.1:18090 AUTH='<你的用户名>:<密码>' \
  ~/dailynotes-server/export.sh ~/notes-export-$(date +%F)

# 历史数据批量导入：见 IMPORT-template.md
```

## 9. 可选加固

- 限 18090/18080 仅 Tailscale 网段：`ufw allow in on tailscale0 to any port 18090` 等（需 `apt install ufw`，先确认不影响 ssh）
- 异地备份（快照同盘防不了磁盘损坏）：`restic` 到第二台机器/对象存储
