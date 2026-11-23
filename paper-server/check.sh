#!/bin/bash
# Paper 서버 상태 점검. 꺼져 있으면 run.sh 로 다시 띄우고 결과를 한 줄로 출력.
HERE="$(cd "$(dirname "$0")" && pwd)"
PAPER_DIR="${PAPER_DIR:-/home/user/paper-server}"

ping_server() {
  python3 -I - <<'EOF'
import socket, struct, json, sys
def varint(n):
    out = b''
    while True:
        b = n & 0x7f; n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n: return out
def read_varint(s):
    n = 0
    for i in range(5):
        b = s.recv(1)[0]; n |= (b & 0x7f) << (7 * i)
        if not b & 0x80: return n
try:
    s = socket.create_connection(("127.0.0.1", 25565), timeout=5)
    host = b"localhost"
    hs = varint(0) + varint(774) + varint(len(host)) + host + struct.pack(">H", 25565) + varint(1)
    s.sendall(varint(len(hs)) + hs + varint(1) + varint(0))
    read_varint(s); read_varint(s); l = read_varint(s)
    data = b''
    while len(data) < l: data += s.recv(l - len(data))
    j = json.loads(data)
    print(f"{j['version']['name']} {j['players']['online']}/{j['players']['max']}명")
except Exception as e:
    print(f"응답 없음 ({e.__class__.__name__})"); sys.exit(1)
EOF
}

status() {
  local p a
  tmux has-session -t paper 2>/dev/null && p=on || p=off
  tmux has-session -t authproxy 2>/dev/null && a=on || a=off
  echo "paper=$p authproxy=$a"
}

if [ "$(status)" = "paper=on authproxy=on" ] && st=$(ping_server); then
  echo "정상: $st, $(status), 메모리 $(free -h | awk '/Mem:/{print $3"/"$2}')"
  exit 0
fi

before="$(status)"
"$HERE/run.sh" >/dev/null 2>&1
for _ in $(seq 1 60); do
  st=$(ping_server) && break
  sleep 3
done
if st=$(ping_server); then
  echo "재시작함 (이전: $before) → 정상: $st"
else
  echo "재시작 실패 (이전: $before) → $st; 로그: $PAPER_DIR/console.log"
  exit 1
fi
