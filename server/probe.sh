#!/usr/bin/env bash
# M0 WebDAV 协议语义联调脚本
# 验证 app-design.md §5.3 的关键决策点：
#   1) PROPFIND 是否返回 getetag
#   2) etag 是否稳定（两次查询一致）
#   3) PUT + If-None-Match: * 是否防覆盖已存在文件
#   4) PUT + If-Match 乐观锁是否生效（错 etag 拒绝）
#   5) 基础动词 MKCOL/GET/DELETE/PROPFIND 正常
# 用法: BASE=http://127.0.0.1:18090 AUTH=probe:probepass123 ./probe.sh
set -u
BASE="${BASE:-http://127.0.0.1:18090}"
AUTH="${AUTH:-probe:probepass123}"
PASS=0; FAIL=0

check() { # name expected actual(desc)
  if [ "$2" = "$3" ]; then echo "PASS  $1  [$3]"; PASS=$((PASS+1))
  else echo "FAIL  $1  (期望 $2, 实际 $3)"; FAIL=$((FAIL+1)); fi
}

status() { # method path [extra curl args...]
  local m=$1 p=$2; shift 2
  curl -s -u "$AUTH" -X "$m" "$BASE$p" -o /tmp/opencode/probe_body -w "%{http_code}" "$@"
}

file_etag() { # path -> 该文件的 getetag（Depth 0）
  curl -s -u "$AUTH" -X PROPFIND -H "Depth: 0" "$BASE$1" | python3 -c "
import sys, xml.etree.ElementTree as ET
try:
    t = ET.fromstring(sys.stdin.read())
    e = t.find('.//{DAV:}getetag')
    print(e.text if e is not None else '')
except Exception:
    print('')"
}

list_etag() { # dir filename -> 目录列表中该文件的 getetag（Depth 1）
  curl -s -u "$AUTH" -X PROPFIND -H "Depth: 1" "$BASE$1" | python3 -c "
import sys, xml.etree.ElementTree as ET
target = '$2'
try:
    t = ET.fromstring(sys.stdin.read())
    for r in t.findall('.//{DAV:}response'):
        href = r.find('{DAV:}href').text
        name = href.rstrip('/').split('/')[-1]
        if name == target:
            e = r.find('.//{DAV:}getetag')
            print(e.text if e is not None else 'MISSING')
            break
except Exception:
    print('ERROR')"
}

echo "== M0 WebDAV 语义联调: $BASE =="

# T1 MKCOL
S=$(status MKCOL /diary)
[ "$S" = 405 ] && S=201   # 已存在视为通过（幂等重跑）
check "T1  MKCOL /diary"            201 "$S"

# T2 PUT 新建
S=$(status PUT /diary/2026-10-21.md -H "Content-Type: text/markdown" --data-binary "# day1 初版")
check "T2  PUT 新建文件"             201 "$S"

# T3 PROPFIND Depth1 列表含 getetag
ETAG=$(list_etag /diary/ 2026-10-21.md)
if [ -n "$ETAG" ] && [ "$ETAG" != "MISSING" ] && [ "$ETAG" != "ERROR" ]; then
  echo "PASS  T3  PROPFIND 列表返回 getetag  [$ETAG]"; PASS=$((PASS+1))
else
  echo "FAIL  T3  PROPFIND 列表返回 getetag  [$ETAG]"; FAIL=$((FAIL+1))
fi

# T4 etag 稳定性
sleep 1
ETAG2=$(list_etag /diary/ 2026-10-21.md)
check "T4  etag 稳定（两次一致）"     "$ETAG" "$ETAG2"

# T5 GET 的 ETag 响应头
GET_ETAG=$(curl -s -u "$AUTH" -D - -o /dev/null "$BASE/diary/2026-10-21.md" | grep -i '^etag:' | tr -d '\r' | cut -d' ' -f2)
if [ -n "$GET_ETAG" ]; then
  echo "PASS  T5  GET 响应带 ETag 头  [$GET_ETAG]"; PASS=$((PASS+1))
else
  echo "FAIL  T5  GET 响应带 ETag 头"; FAIL=$((FAIL+1))
fi

# T6-T8 条件请求语义【探测式】：服务端可能不支持，如实报告，不算失败
S=$(status PUT /diary/2026-10-21.md -H 'If-None-Match: *' --data-binary "覆盖尝试")
echo "INFO  T6  PUT If-None-Match:* → $S $([ "$S" = 412 ] && echo '(防覆盖生效)' || echo '(无视条件头，无条件覆盖)')"

S=$(status PUT /diary/2026-10-21.md -H "If-Match: $ETAG" --data-binary "# day1 v2 改")
echo "INFO  T7  PUT If-Match 正确etag → $S"

S=$(status PUT /diary/2026-10-21.md -H 'If-Match: "bogus-etag"' --data-binary "错误覆盖")
echo "INFO  T8  PUT If-Match 错误etag → $S $([ "$S" = 412 ] && echo '(乐观锁生效)' || echo '(乐观锁不支持:无条件放行)')"
CONDAUTH=$([ "$S" = 412 ] && echo yes || echo no)

# T10 DELETE
S=$(status DELETE /diary/2026-10-21.md)
check "T9  DELETE"                    204 "$S"

# T11 删除后 PROPFIND 无此文件
S=$(status PROPFIND /diary/2026-10-21.md -H "Depth: 0")
check "T10 删除后 PROPFIND 404"       404 "$S"

echo
echo "== 结论: $PASS PASS / $FAIL FAIL, 服务端条件PUT支持: $CONDAUTH =="
[ "$FAIL" = 0 ]
