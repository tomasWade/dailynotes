# 历史日记批量导入

> 把已有历史数据整理成下面的**文件集结构**，用 `import.sh` 一键灌入服务端。适用于从其他工具迁移、或整理旧记录导入。
> 增量幂等：已存在的文件跳过不覆盖，重跑安全。

---

## 交付物：`import-payload/` 目录

```
import-payload/
  diary/
    2019-03-14.md        # 一个文件 = 一天，文件名 = 当天日期（本地日期语义）
    2019-03-13.md
    2018-06-02.md
    ...
  notes/                 # 可选：不属于按日日记的零散条目
    20190314T083000-a1b2.md
  attachments/            # 可选：仅当有图片
    2019-03/
      20190314_083000_ab12.jpg
```

`diary/` 必需；`notes/`、`attachments/` 按需（纯文字数据可整目录省略）。

## 文件规格

| 规则 | 说明 |
|---|---|
| 文件名 `YYYY-MM-DD.md` | App 按此模式识别日记并挂到日历视图；日期必须是**有效日历日**（不能 0000-00-00、不能 2021-02-30） |
| 编码 UTF-8，无 BOM | App 直接按 UTF-8 读 |
| **无 frontmatter、无标题行** | 文件第一行就是正文（App 预览 300 字从头截取） |
| 内容 = 当天日记正文原样 | markdown 语法（`#`、`-` 列表等）可用可不用，App 原样展示 |
| 同一天多条内容 → 合并为一个文件 | 多条之间用一个空行分隔；**不允许同日期两个文件**（后传的会覆盖先传的） |
| 笔记文件名 `YYYYMMDDTHHMMSS-xxxx.md` | 后缀 xxxx 为 **4 位小写十六进制**（如 `-a1b2`）；`-A1B2` 或其他后缀不会被 App 的笔记流识别 |
| 图片引用（如有） | md 里写相对路径 `![](../attachments/2019-03/xxx.jpg)`（从 diary/ 视角）；图片文件放对应月桶目录（`YYYY-MM/`） |

## 日期不确定的条目

推不出具体日期的内容**不要塞进 diary/**——放 `notes/`（日期用大概时间），或单独列出人工确认，**宁缺毋滥**。

## 灌入（服务端机器上）

```bash
# 先手动拍一份快照（习惯：大事前先快照）
~/dailynotes-server/backup.sh ~/dailynotes-server/data ~/dailynotes-server/snapshots 30

# 灌入（增量：已存在的 SKIP 不覆盖）
BASE=http://127.0.0.1:18090 AUTH='<你的用户名>:<密码>' \
  ~/dailynotes-server/import.sh ~/import-payload/

# 全量重传（确要覆盖时才加）：
#   ... import.sh ~/import-payload/ --force
```

## 验证

```bash
# 方法1：反向导出比对（逐字节一致 = 灌入无误；payload 带 notes/attachments 时切勿排除，否则等于没验图片）
BASE=http://127.0.0.1:18090 AUTH='<你的用户名>:<密码>' \
  ~/dailynotes-server/export.sh ~/verify-export
diff -r ~/import-payload ~/verify-export
# 注：export.sh 只导出实际存在的文件（不会生成空目录），纯 diary payload 无预期差异

# 方法2：手机 App 点「同步」，主页信息流/日历应出现全部导入日期
```

## 环境变量说明

`import.sh` / `export.sh` 通过 `BASE` / `AUTH` 环境变量指定服务器与账号，按部署实际填入。
