#!/usr/bin/env bash
# 批量导入本地数据到服务器（WebDAV API 用法范例）
# 语义：增量导入——服务器已有的文件跳过（不覆盖，防 LWW 误伤）；--force 全量重传
# 用法: ./import.sh <本地目录> [--force]
#   本地目录结构须为 diary/ notes/ attachments/（与服务器同构）
set -u
BASE="${BASE:-http://127.0.0.1:18090}"
AUTH="${AUTH:-probe:probepass123}"
FORCE=0
[ "${2:-}" = "--force" ] && FORCE=1
SRC="${1:?用法: import.sh <本地目录> [--force]}"

[ -d "$SRC" ] || { echo "目录不存在: $SRC"; exit 1; }

# 服务器已有文件清单（href set）
existing() {
  for d in diary notes attachments; do
    curl -s -u "$AUTH" -X PROPFIND -H "Depth: 1" "$BASE/$d/" 2>/dev/null
  done | python3 -c "
import sys, xml.etree.ElementTree as ET
try:
    for chunk in sys.stdin.read().split('<?xml'):
        pass  # 多个文档拼接，逐个解析
except Exception:
    pass"
}

# 逐文件 PROPFIND 判存在（简单可靠：文件数少时开销可忽略）
remote_has() { # path -> 0/1
  local code
  code=$(curl -s -u "$AUTH" -o /dev/null -w "%{http_code}" -X PROPFIND -H "Depth: 0" "$BASE/$1")
  [ "$code" = 207 ]
}

count_new=0; count_skip=0; count_err=0
cd "$SRC" || exit 1

# 确保三个目录存在（幂等）
for d in diary notes attachments; do
  curl -s -u "$AUTH" -X MKCOL "$BASE/$d/" -o /dev/null
done

# 递归找文件（限定三类目录）
find diary notes attachments -type f 2>/dev/null | while read -r f; do
  # 附件子目录逐级 MKCOL（attachments/2026-10/ 等）
  dir=$(dirname "$f")
  case "$dir" in
    diary|notes|attachments) ;;
    *) curl -s -u "$AUTH" -X MKCOL "$BASE/$dir/" -o /dev/null ;;
  esac

  if [ "$FORCE" = 0 ] && remote_has "$f"; then
    echo "SKIP  $f"
    continue
  fi
  code=$(curl -s -u "$AUTH" -X PUT -T "$f" -H "Content-Type: application/octet-stream" -o /dev/null -w "%{http_code}" "$BASE/$f")
  if [ "$code" = 201 ] || [ "$code" = 204 ]; then
    echo "PUT   $f [$code]"
  else
    echo "ERR   $f [$code]" >&2
  fi
done
echo "导入完成（--force=$FORCE）"
