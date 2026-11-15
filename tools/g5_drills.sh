#!/bin/bash
# Gate G5 崩溃恢复演练：3 轮 "开一局 → 落盘 → 停服 → 重启 → 逐字段比对"。
#
# 前置：能起 dev 服（./gradlew runServer）。脚本会自己起停服务端，跑完最后一轮后
#       服务端保持运行（after 段只 abort，不 stop），方便紧接着做回滚演练。
# 产物：run/g5_drills.log（gitignored，不入库）。
#
# 用法：
#   bash tools/g5_drills.sh
#   PY=python bash tools/g5_drills.sh          # 有系统 Python 时
#   PY=./run/py-embed/python.exe bash tools/g5_drills.sh
set -u
cd "$(dirname "$0")/.."
PY="${PY:-./run/py-embed/python.exe}"
export RCON_PORT=25576
export RCON_PASSWORD=nbwm0
LOG="run/server-console.log"
DRILL_LOG="run/g5_drills.log"
: > "$DRILL_LOG"

log() { echo "$@" | tee -a "$DRILL_LOG"; }

port_down() {
  for _ in $(seq 1 60); do
    if ! netstat -ano | grep -q ":25576 .*LISTENING"; then return 0; fi
    sleep 2
  done
  return 1
}

wait_ready() {
  for _ in $(seq 1 120); do
    if grep -aq "Done (" "$LOG" 2>/dev/null; then return 0; fi
    sleep 2
  done
  return 1
}

# 等真正干净的 LOBBY：phase=LOBBY + queue=0 + teams=0。
# 为什么必须等：上一支探针/上一段 after 可能还在 RETURN，此时 cww start 会被状态机拒绝，
# restart_probe before 就会红；这不是恢复能力的问题，是夹具时序。
wait_lobby() {
  for _ in $(seq 1 90); do
    st=$("$PY" -I -u -X utf8 tools/rcon.py "cww state" 2>/dev/null)
    if echo "$st" | grep -q "phase=LOBBY" && echo "$st" | grep -q "teams=0" && echo "$st" | grep -q "queue=0"; then
      return 0
    fi
    if ! echo "$st" | grep -q "phase=LOBBY"; then
      "$PY" -I -u -X utf8 tools/rcon.py "cww abort" >/dev/null 2>&1
    fi
    sleep 1
  done
  return 1
}

ensure_server() {
  if netstat -ano | grep -q ":25576 .*LISTENING"; then return 0; fi
  log "server not running; starting"
  (./gradlew runServer > "$LOG" 2>&1) &
  wait_ready && log "server ready" || return 1
}

ensure_server || { log "ERROR: 无法启动服务端"; exit 1; }

for i in 1 2 3; do
  log "===== DRILL $i BEFORE ====="
  wait_lobby && log "clean lobby" || log "WARN: lobby not clean"
  "$PY" -I -u -X utf8 tools/restart_probe.py before >> "$DRILL_LOG" 2>&1
  log "before rc=$?"
  port_down || log "WARN: 25576 仍在监听"

  log "===== DRILL $i RESTART ====="
  (./gradlew runServer > "$LOG" 2>&1) &
  wait_ready && log "server ready" || log "ERROR: server not ready"

  log "===== DRILL $i AFTER ====="
  "$PY" -I -u -X utf8 tools/restart_probe.py after >> "$DRILL_LOG" 2>&1
  log "after rc=$?"
done

log "===== ALL DRILLS DONE ====="
