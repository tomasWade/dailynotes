# dailynotes 服务端部署手册（mac-mini / Debian）

> 部署目标机是**装了 Debian Linux 的 Mac mini**（用户确认，非 macOS；ARM/Intel 均可，SFTPGo 镜像多架构）。
> 你亲手执行的部署文档，照抄命令即可。
> 架构：SFTPGo 容器（WebDAV）+ 每日快照，手机经 Tailscale 访问，无 HTTPS（WireGuard 隧道加密，等效专线）。
> 所有 `<尖括号>` 是占位符，执行时替换。

---

## 0. 前提检查（mac-mini 上）

```bash
# Debian 版本
cat /etc/debian_version
# docker + compose 插件
docker --version && docker compose version
# Tailscale 已登录且与本机同一 tailnet（记下 mac-mini 的 Tailscale IP）
tailscale ip -1    # 输出 100.x.x.x，记为 <mac-mini-ts-ip>
```

缺什么装什么：`sudo apt install docker.io docker-compose-plugin` / Tailscale 官方脚本安装。

## 1. 从开发机拷贝服务端文件（开发机上执行）

```bash
# 开发机上执行。<mac-mini> 换 mac-mini 的 Tailscale IP 或 ssh 别名
rsync -av \
  --exclude probe-files --exclude sftpgo-data --exclude snapshots \
  ~/tmp/dailynotes/server/ <mac-mini>:~/dailynotes-server/
```

带过去的文件：`docker-compose.yml / loaddata.json / probe.sh / backup.sh / import.sh / export.sh / gen-testdata.py`（测试数据和元数据库已排除）。

## 2. 建正式用户（mac-mini 上编辑 loaddata.json）

```bash
cd ~/dailynotes-server
nano loaddata.json    # 或 vi
```

整份替换为（**两个密码都换成你自己生成的强密码**，比如 `openssl rand -base64 18` 生成）：

```json
{
  "admins": [{"username":"admin","password":"<管理界面密码>","status":1}],
  "users": [{"username":"haotianyu","password":"<App登录密码>","status":1,"home_dir":"/srv/haotianyu","permissions":{"/":["*"]}}]
}
```

> 注意：loaddata 只在**首次建号**时生效；以后改密码走 web 管理界面（见 §8）。

## 3. 替换 docker-compose.yml 为正式版

```bash
nano docker-compose.yml   # 整份替换为下面内容
```

```yaml
services:
  sftpgo:
    image: drakkan/sftpgo:latest   # arm64 原生支持（实测 2.7.6）
    container_name: sftpgo
    environment:
      # 坑1: 镜像默认不起 WebDAV，必须显式指定端口
      - SFTPGO_WEBDAVD__BINDINGS__0__PORT=8090
      # 坑2: 默认 admin 环境变量不生效，从 JSON 导入用户
      - SFTPGO_LOADDATA_FROM=/etc/sftpgo/loaddata.json
    ports:
      # 家庭 NAT 内网，无公网暴露；Basic auth 保护
      # 手机 App 填: http://<mac-mini-ts-ip>:18090
      - "18080:8080"   # web 管理界面
      - "18090:8090"   # WebDAV（App 打这里）
    volumes:
      - ./sftpgo-data:/var/lib/sftpgo     # 用户/配额元数据
      - ./data:/srv/haotianyu             # 你的全部笔记数据（快照/备份就备它）
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
docker logs sftpgo 2>&1 | grep -c "WebDAV server not started" || true   # 输出 0 = WebDAV 正常起来了
docker ps --filter name=sftpgo --format "{{.Status}}"                    # 应为 Up，若 Restarting 见上面属主坑

# 协议语义验证（7 项应全 PASS，T6-T8 是条件 PUT 语义探测不计分：SFTPGo 无视条件头属已知预期）
BASE=http://127.0.0.1:18090 AUTH=haotianyu:<App登录密码> ./probe.sh
```

**拉不动镜像时**（国内网络）：用镜像前缀拉取再改 tag：

```bash
docker pull docker.1ms.run/drakkan/sftpgo:latest
docker tag docker.1ms.run/drakkan/sftpgo:latest drakkan/sftpgo:latest
docker compose up -d
```

## 5. 挂每日快照 cron（正式数据的生命线）

```bash
crontab -e    # 加一行（<user> 换成 mac-mini 登录用户名）：
```

```
0 4 * * * /home/<user>/dailynotes-server/backup.sh /home/<user>/dailynotes-server/data /home/<user>/dailynotes-server/snapshots 30 >> /home/<user>/dailynotes-server/backup.log 2>&1
```

每天 04:00 快照，保留 30 份（硬链接时间机器，增量空间）。验证（不等明天，手动跑一次）：

```bash
~/dailynotes-server/backup.sh ~/dailynotes-server/data ~/dailynotes-server/snapshots 30
ls ~/dailynotes-server/snapshots/
```

## 6. 备份 APK 签名 keystore（开发机上执行，30 秒）

当前 APK 用 debug key 签名。**这个文件丢了 = 以后所有版本无法覆盖安装**，必须异地备一份：

```bash
ssh <mac-mini> 'mkdir -p ~/dailynotes-server/keystore-backup'
scp ~/.android/debug.keystore <mac-mini>:~/dailynotes-server/keystore-backup/
```

> keystore 属私钥，永远不上 GitHub / 任何公开渠道。放这里（私有机器）即可。

## 7. 手机切换流程（顺序重要，已拍板）

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | （已完成）mac-mini 服务跑起来，probe.sh 全绿 | — |
| 2 | 手机 App → 设置：服务器改 `http://<mac-mini-ts-ip>:18090`，用户 `haotianyu`，密码你设的；保存 → 回主页点「同步」 | **手机上的测试数据全部消失**（50 假日记+41 假图被清，这是删除传播按预期工作，不是 bug） |
| 3 | 数据整理完成后灌入（见 `IMPORT-template.md`） | 服务端出现真实日记 |
| 4 | 手机再点「同步」 | 真实数据全部拉到手机 |

## 8. 服务端更新（两类，别搞混）

**A. 上游镜像更新**（SFTPGo 出新版/安全补丁）：容器无状态，随便升

```bash
cd ~/dailynotes-server && docker compose pull && docker compose up -d
```

**B. 项目文件更新**（backup.sh/probe.sh/compose 等改了）——开发机上执行 rsync 推送：

```bash
# <mac-mini> 换 Tailscale IP；目标路径换实际部署位置（本次实际部署在 ~/dockers/dailynotes）
rsync -av \
  --exclude probe-files --exclude sftpgo-data --exclude snapshots --exclude data \
  --exclude loaddata.json --exclude backup.log \
  --exclude keystore-backup --exclude DEPLOY-macmini.md \
  ~/tmp/dailynotes/server/ <mac-mini>:~/dailynotes-server/
```

排除项的含义（**搞错会出事**）：
- `loaddata.json`：里面是你手填的密码，覆盖即丢失（SFTPGo 用户已建号，密码管理走 web 界面，此文件只在首次建号时被读）
- `data / sftpgo-data / snapshots`：全部业务数据与快照
- `keystore-backup / backup.log`：mac-mini 本地产物

推完后：
- 只有 `.sh` 脚本变了 → 什么都不用做（下次执行自动生效）
- `docker-compose.yml` 变了 → `docker compose up -d`（容器按新配置重建，数据在卷外不受影响）

## 9. 日常运维速查

```bash
# 改密码 / 建用户 / 看磁盘占用：web 管理界面
# 浏览器打开 http://<mac-mini-ts-ip>:18080  → admin / <管理界面密码>
#   Users → haotianyu → Edit → Password（改完手机 App 设置里同步更新）

# 手动快照（大事前先拍一份，如导入前）
~/dailynotes-server/backup.sh ~/dailynotes-server/data ~/dailynotes-server/snapshots 30

# 恢复某个文件（从快照拷回，例：昨天的日记）
cp ~/dailynotes-server/snapshots/<时间戳>/diary/2026-10-21.md ~/dailynotes-server/data/diary/

# 全量导出到本地目录（换机/双保险）
BASE=http://127.0.0.1:18090 AUTH=haotianyu:<密码> ~/dailynotes-server/export.sh ~/notes-export-$(date +%F)

# 升级 SFTPGo
cd ~/dailynotes-server && docker compose pull && docker compose up -d   # 数据都在卷外，随便升
```

## 10. 可选加固（不急，有空再说）

- 限 18090/18080 仅 Tailscale 网段访问：`ufw allow in on tailscale0 to any port 18090 && ufw allow in on tailscale0 to any port 18080`（需 `apt install ufw` 并确认不影响 ssh）
- 异地备份（防磁盘损坏，快照同盘防不了）：`restic` 到第二台机器/对象存储，v2 计划
