# 历史日记导入模板（给数据整理 session 的规格书）

> 目标：把历史数据整理成下面这个**文件集结构**，然后用 `import.sh` 一键灌入服务端。
> 日期推算由整理方负责（数据是每行一条、整体倒序、部分年份无具体日期——按上下文推，推不出的行宁可单独列出人工确认，不要瞎猜）。

---

## 交付物：`import-payload/` 目录

```
import-payload/
  diary/
    2019-03-14.md        # 一个文件 = 一天，文件名 = 当天日期（本地日期语义）
    2019-03-13.md
    2018-06-02.md
    ...
  attachments/            # 仅当有图片时才需要（纯文字数据可整目录省略）
    2019-03/
      20190314_083000_ab12.jpg
```

## 文件规格（每条硬规则都有理由）

| 规则 | 说明 |
|---|---|
| 文件名 `YYYY-MM-DD.md` | App 按此模式识别日记并挂到日历视图；日期必须是**有效日历日**（不能 0000-00-00、不能 2021-02-30） |
| 编码 UTF-8，无 BOM | App 直接按 UTF-8 读 |
| **无 frontmatter、无标题行** | 文件第一行就是正文（App 预览 300 字从头截取） |
| 内容 = 当天日记正文原样 | 保留原始文字；markdown 语法（`#`、`-` 列表等）可用可不用，App 原样展示 |
| 同一天多条内容 → 合并为一个文件 | 多条之间用一个空行分隔；**不允许出现两个同名日期文件**（后写的会覆盖先写的） |
| 一行一条的原始数据 | 换行原样保留即可，不做转义 |
| 图片引用（如有） | md 里写相对路径 `![](../attachments/2019-03/xxx.jpg)`（从 diary/ 视角）；图片文件放对应月桶目录 |

## 不确定日期的处理（重要）

- 完全推不出日期的条目：**不要塞进 diary/**。放到 `import-payload/notes/`（文件名格式 `YYYYMMDDTHHMMSS-xxxx.md`——**后缀必须是 4 位小写十六进制**，如 `-a1b2`，App 的笔记流按此模式识别，`-A1B2` 或 `.TXT` 不会被识别；日期用你知道的大概时间或导入当天），App 的「笔记」流会展示它们
- 或单独输出 `uncertain.txt` 列出来人工定，**宁缺毋滥**

## 灌入命令（mac-mini 上，数据整理完成后）

```bash
# 先手动拍一份快照（养成习惯：大事前先快照）
~/dailynotes-server/backup.sh ~/dailynotes-server/data ~/dailynotes-server/snapshots 30

# 灌入（增量：已存在的文件 SKIP 不覆盖；重跑安全）
BASE=http://127.0.0.1:18090 AUTH=haotianyu:<密码> \
  ~/dailynotes-server/import.sh ~/import-payload/

# 全量重传（确要覆盖时才加）：
#   ... import.sh ~/import-payload/ --force
```

## 验证

```bash
# 方法1：反向导出比对（逐字节一致 = 灌入无误；payload 带 notes/attachments 时切勿排除，否则等于没验图片）
BASE=http://127.0.0.1:18090 AUTH=haotianyu:<密码> \
  ~/dailynotes-server/export.sh ~/verify-export
diff -r ~/import-payload ~/verify-export
# 注：export.sh 只导出实际存在的文件（不会生成空目录），纯 diary payload 无预期差异

# 方法2：手机 App 点「同步」，主页信息流/日历应出现全部导入日期
```

## 环境变量说明

`import.sh` / `export.sh` 通过 `BASE` / `AUTH` 环境变量指定服务器与账号，默认值是联调环境的（probe:probepass123），正式环境必须显式传入。
