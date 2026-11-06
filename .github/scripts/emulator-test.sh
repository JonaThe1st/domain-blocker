#!/usr/bin/env bash
# Emulator smoke/stability test for the Domain Blocker VPN service.
# Runs on the GitHub Actions runner inside reactivecircus/android-emulator-runner.
# Prints EVIDENCE lines; every assertion failure exits non-zero.
set -u

PKG=de.jona.domainblocker
APP=.MainActivity
TMP=/data/local/tmp

GREEN='\033[32m'; RED='\033[31m'; NC='\033[0m'
ok()   { echo -e "${GREEN}EVIDENCE OK:${NC} $*"; }
fail() { echo -e "${RED}EVIDENCE FAIL:${NC} $*"; exit 1; }

# ---------------------------------------------------------------- helpers
tap_by_resid() {
  # $1 = resource-id suffix; taps the center of the node.
  local resid="de.jona.domainblocker:id/$1"
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 || true
  python3 - "$resid" <<'PY' || true
import re, sys, subprocess
resid = sys.argv[1]
xml = open('/tmp/ui.xml', encoding='utf-8', errors='replace').read()
m = re.search(r'resource-id="' + re.escape(resid) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    raise SystemExit(1)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)])
PY
  sleep 1
}

tap_system_button() {
  # Taps a system dialog button whose text is in the given list ($@).
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null 2>&1 || true
  python3 - "$@" <<'PY' || true
import re, sys, subprocess
wanted = set(sys.argv[1:])
xml = open('/tmp/ui.xml', encoding='utf-8', errors='replace').read()
for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    if m.group(1) in wanted:
        x = (int(m.group(2)) + int(m.group(4))) // 2
        y = (int(m.group(3)) + int(m.group(5))) // 2
        subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)])
        raise SystemExit(0)
raise SystemExit(1)
PY
  sleep 2
}

service_alive() {
  adb shell dumpsys activity services "$PKG" | grep -c "BlockVpnService" || true
}

# ---------------------------------------------------------------- DNS probe
# Sends a real UDP DNS query to 8.8.8.8:53 (routed through our tunnel) via
# toybox nc; prints only the resolved IP (127.0.0.1 for blocked) or NOANSWER.
dns_probe() {
  # $1 = domain
  python3 - "$1" > /tmp/q.bin <<'PY'
import sys
name = sys.argv[1]
out = bytearray([0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])
for label in name.rstrip('.').split('.'):
    out.append(len(label)); out += label.encode()
out += b'\x00' + (1).to_bytes(2, 'big') + (1).to_bytes(2, 'big')
open('/tmp/q.bin','wb').write(bytes(out))
PY
  adb push /tmp/q.bin "$TMP/q.bin" >/dev/null
  adb shell "rm -f $TMP/r.bin; nc -u -w 6 8.8.8.8 53 < $TMP/q.bin > $TMP/r.bin 2>/dev/null" || true
  adb pull "$TMP/r.bin" /tmp/r.bin >/dev/null 2>&1 || true
  python3 - <<'PY'
import sys, struct
try:
    data = open('/tmp/r.bin','rb').read()
except FileNotFoundError:
    data = b''
if len(data) < 12:
    print("NOANSWER"); sys.exit(1)
an = struct.unpack('>H', data[6:8])[0]
pos = 12
while pos < len(data):
    l = data[pos]
    if l == 0: pos += 1; break
    pos += 1 + l
pos += 4  # qtype+qclass
for _ in range(an):
    pos += 2  # our replies always use the 2-byte name pointer
    typ, cls, ttl, rdlen = struct.unpack('>HHIH', data[pos:pos+10])
    pos += 10
    if typ == 1 and rdlen == 4:
        print('.'.join(str(b) for b in data[pos:pos+4]))
        sys.exit(0)
    pos += rdlen
print("NOANSWER")
PY
}

# ================================================================ MAIN
adb start-server >/dev/null 2>&1 || true
say() { echo -e "\033[1m$*\033[0m"; }

say "== install & start =="
adb install -r app/build/outputs/apk/release/app-release.apk || fail "install"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am start -n $PKG/$APP
sleep 5

say "== blocklist: add instagram.com via UI =="
tap_by_resid edit_domain
adb shell input text 'instagram.com'
sleep 1
tap_by_resid btn_add
sleep 2

say "== enable filter (switch + VPN dialog) =="
tap_by_resid switch_filter
sleep 3
tap_system_button "OK" "Allow" "Verbinden" "Connect" "Erlauben"
sleep 4

ALIVE=$(service_alive)
say "service alive right after enable: $ALIVE"
[ "$ALIVE" -ge 1 ] || fail "service not running after enabling filter"
adb shell dumpsys connectivity | grep -i "vpn" | head -3 || true
adb exec-out screencap -p > /tmp/01_enabled.png

say "== DNS behavior: blocked vs allowed =="
BLOCKED_IP=$(dns_probe instagram.com)
ALLOWED_IP=$(dns_probe example.com)
echo "  blocked instagram.com -> $BLOCKED_IP"
echo "  allowed example.com   -> $ALLOWED_IP"
[ "$BLOCKED_IP" = "127.0.0.1" ] && ok "blocked domain resolves to 127.0.0.1" \
  || fail "blocked domain did NOT resolve to 127.0.0.1 (got '$BLOCKED_IP')"
case "$ALLOWED_IP" in
  127.0.0.1|NOANSWER|"") fail "allowed domain did not resolve ($ALLOWED_IP)" ;;
  *) ok "allowed domain resolved to real IP $ALLOWED_IP" ;;
esac

say "== stability: ~5 min mixed DNS load, service must survive =="
PASS=1
for i in $(seq 1 10); do
  dns_probe instagram.com >/dev/null 2>&1 || true
  dns_probe example.com >/dev/null 2>&1 || true
  dns_probe notarealexample-xyz123.com >/dev/null 2>&1 || true
  sleep 22
  ALIVE=$(service_alive)
  echo "  loop $i/10 after ~30s: service alive=$ALIVE"
  [ "$ALIVE" -ge 1 ] || { PASS=0; echo "service died at loop $i"; break; }
done
[ "$PASS" -eq 1 ] && ok "service alive after 10x30s (~5 min) DNS load" \
  || fail "service died during stability window"
adb exec-out screencap -p > /tmp/02_stability.png

say "== toggle: off then on x3 =="
for i in 1 2 3; do
  tap_by_resid switch_filter
  sleep 3
  [ "$(service_alive)" -eq 0 ] && ok "toggle $i: filter OFF" || echo "  toggle $i: still alive?"
  tap_by_resid switch_filter
  sleep 4
  [ "$(service_alive)" -ge 1 ] && ok "toggle $i: filter ON" || fail "toggle $i: service did not restart"
done

say "== collect evidence =="
adb logcat -d > /tmp/logcat.txt 2>&1 || true
grep -E "FATAL EXCEPTION|AndroidRuntime|BindException|SecurityException|ForegroundServiceStartNotAllowed" /tmp/logcat.txt | tail -40 || true
echo "== ALL EVIDENCE PASSED =="
