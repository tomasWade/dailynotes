#!/usr/bin/env bash
# 服务端数据定时快照（rsync --link-dest 硬链接时间机器）
# 作用：防误删 / 误改 / LWW 丢写后可回滚。
# 注意：快照与数据同盘，防「人为错误」不防「磁盘损坏」；异地备份见 development-plan v2。
#
# 用法: ./backup.sh [数据目录] [快照目录] [保留份数]
# cron 示例（VPS，每天 04:00）:
#   0 4 * * * /opt/dailynotes/server/backup.sh /opt/dailynotes/server/data /opt/dailynotes/server/snapshots 30
set -eu

SRC=$(realpath "${1:-./probe-files}")
DST=$(realpath "${2:-./snapshots}")
KEEP="${3:-30}"

[ -d "$SRC" ] || { echo "数据目录不存在: $SRC"; exit 1; }
mkdir -p "$DST"

STAMP=$(date +%F_%H%M%S)
TODAY="$DST/$STAMP"
LAST=$(ls -1d "$DST"/20* 2>/dev/null | sort | tail -1 || true)

if [ -n "$LAST" ] && [ "$LAST" = "$TODAY" ]; then
  echo "本轮已快照: $TODAY"; exit 0
fi

if [ -n "$LAST" ]; then
  rsync -a --delete --link-dest="$LAST" "$SRC"/ "$TODAY"/
else
  rsync -a "$SRC"/ "$TODAY"/
fi

# 旋转：只保留最近 KEEP 份
ls -1d "$DST"/20* | sort | head -n -"$KEEP" | xargs -r rm -rf

TOTAL=$(ls -1d "$DST"/20* | wc -l)
echo "快照完成: $TODAY（共 $TOTAL 份，保留 $KEEP）"
