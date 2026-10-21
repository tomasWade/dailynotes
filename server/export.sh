#!/usr/bin/env bash
# 全量导出服务器数据到本地目录（WebDAV API 用法范例）
# 语义：PROPFIND 递归枚举 → GET 全部 → 落盘（镜像服务器当前状态）
# 用法: ./export.sh <目标目录>
set -u
BASE="${BASE:-http://127.0.0.1:18090}"
AUTH="${AUTH:-probe:probepass123}"
DST="${1:?用法: export.sh <目标目录>}"
mkdir -p "$DST"

# 列出目录直接子项：输出 "type path"（type: dir/file）
ls_dir() { # server_dir
  curl -s -u "$AUTH" -X PROPFIND -H "Depth: 1" "$BASE/$1/" | python3 -c "
import sys, xml.etree.ElementTree as ET
ns = {'D': 'DAV:'}
try:
    root = ET.fromstring(sys.stdin.read())
except Exception:
    sys.exit(0)
base = '$1'.rstrip('/') + '/'
for r in root.findall('.//D:response', ns):
    href = r.find('D:href', ns).text.lstrip('/')
    name = href.rstrip('/').split('/')[-1]
    if not name or base.rstrip('/') == href.rstrip('/'):
        continue
    is_dir = r.find('.//D:resourcetype/D:collection', ns) is not None
    print(('dir ' if is_dir else 'file ') + base + name)"
}

# 递归枚举所有文件
walk() { # dir
  ls_dir "$1" | while read -r type path; do
    if [ "$type" = dir ]; then
      walk "$path"
    else
      echo "$path"
    fi
  done
}

n=0
for top in diary notes attachments; do
  curl -s -u "$AUTH" -X MKCOL "$BASE/$top/" -o /dev/null   # 空库时幂等建目录
  walk "$top"
done | while read -r f; do
  local_dir="$DST/$(dirname "$f")"
  mkdir -p "$local_dir"
  code=$(curl -s -u "$AUTH" -o "$DST/$f" -w "%{http_code}" "$BASE/$f")
  echo "GET   $f [$code]"
done
echo "导出完成 → $DST"
