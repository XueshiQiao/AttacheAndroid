#!/usr/bin/env bash
#
# Configure Android battery optimization & unrestricted background network
# for ConnectBot / Attaché Android over ADB.
#
# Usage:
#   ./scripts/configure-background-keepalive.sh [package_name] [adb_serial]
#
# Examples:
#   ./scripts/configure-background-keepalive.sh
#   ./scripts/configure-background-keepalive.sh org.connectbot.debug 403GAA008U65200
#   ./scripts/configure-background-keepalive.sh org.connectbot

set -euo pipefail

PKG="${1:-org.connectbot.debug}"
SERIAL="${2:-}"

ADB_CMD=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB_CMD+=( -s "$SERIAL" )
fi

echo "==> Checking ADB connection..."
"${ADB_CMD[@]}" get-state >/dev/null

echo "==> Configuring background keep-alive & unrestricted network for: $PKG"

"${ADB_CMD[@]}" shell "
set -e
PKG='$PKG'

# 1. Add to Doze / Battery Optimization whitelist (Unrestricted battery)
dumpsys deviceidle whitelist +\$PKG >/dev/null

# 2. Extract UID from deviceidle whitelist or package dump
UID_VAL=\$(dumpsys deviceidle whitelist | grep -F \",\$PKG,\" | head -n1 | awk -F',' '{print \$3}' | tr -d '\r')
if [ -z \"\$UID_VAL\" ]; then
  UID_VAL=\$(dumpsys package \$PKG | grep -m1 'userId=' | sed -E 's/.*userId=([0-9]+).*/\1/' | tr -d '\r')
fi

echo \"    Package : \$PKG\"
echo \"    UID     : \$UID_VAL\"

# 3. Allow running in background & wake locks via AppOps
cmd appops set \$PKG RUN_IN_BACKGROUND allow
cmd appops set \$PKG RUN_ANY_IN_BACKGROUND allow
cmd appops set \$PKG WAKE_LOCK allow

# 4. Set App Standby bucket to active (exempt from standby throttling)
am set-standby-bucket \$PKG active

# 5. Grant POST_NOTIFICATIONS so foreground service notification stays alive on Android 13+
pm grant \$PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 6. Whitelist UID in Network Policy Manager (unrestricted background network/data)
if [ -n \"\$UID_VAL\" ]; then
  cmd netpolicy add restrict-background-whitelist \$UID_VAL
  cmd netpolicy remove restrict-background-blacklist \$UID_VAL 2>/dev/null || true
fi

echo ''
echo '=== Verification ==='
echo -n '[1] Doze Whitelist       : '
dumpsys deviceidle whitelist | grep -F \",\$PKG,\" || echo 'NOT FOUND'
echo -n '[2] RUN_IN_BACKGROUND    : '
cmd appops get \$PKG RUN_IN_BACKGROUND | tr '\n' ' '
echo ''
echo -n '[3] RUN_ANY_IN_BACKGROUND: '
cmd appops get \$PKG RUN_ANY_IN_BACKGROUND | tr '\n' ' '
echo ''
echo -n '[4] Standby Bucket       : '
am get-standby-bucket \$PKG
echo -n '[5] NetPolicy Whitelist  : '
cmd netpolicy list restrict-background-whitelist | grep -w \"\$UID_VAL\" || echo 'NOT FOUND'
"

echo "==> Done! $PKG is configured to stay connected in the background."
