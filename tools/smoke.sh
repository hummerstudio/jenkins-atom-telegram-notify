#!/usr/bin/env bash
# The bot endpoint, the body it must carry, the token in the log, and the "200 but refused" answer.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

log="$(mktemp)"; port=$((19000 + RANDOM % 900))
python3 tools/fake_telegram.py "$port" "$log" & server=$!
trap 'kill $server 2>/dev/null; wait $server 2>/dev/null' EXIT
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port/" && break; sleep 0.1; done

post() {
  local dir; dir="$(mktemp -d)"
  printf '%s' "$1" >"$dir/input.json"
  run "$dir" >"$dir/atom.log" 2>&1
  echo "$dir"
}

dir=$(post "{\"token\": \"123:SECRET\", \"chatId\": \"-100123\", \"text\": \"build ok\", \"apiBase\": \"http://127.0.0.1:$port\", \"threadId\": \"7\"}")
status "$dir" | grep -q success || fail "send: $(cat "$dir/output.json")"
[ "$(output "$dir" messageId)" = "4242" ] || fail "message id: $(cat "$dir/output.json")"
[ "$(output "$dir" sent)" = "true" ] || fail "sent flag"
tail -1 "$log" | grep -q '"path": "/bot123:SECRET/sendMessage"' || fail "endpoint: $(tail -1 "$log")"
tail -1 "$log" | python3 -c 'import json,sys; b=json.loads(json.load(sys.stdin)["body"]); assert b["chat_id"]=="-100123" and b["text"]=="build ok" and b["message_thread_id"]=="7", b' || fail "body: $(tail -1 "$log")"
grep -q "bot\*\*\*/sendMessage" "$dir/atom.log" || fail "the token is in the log: $(grep -F '[telegram]' "$dir/atom.log")"

# a refused send must fail even though the API answers with a body
kill $server 2>/dev/null; wait $server 2>/dev/null
port2=$((port + 1))
python3 tools/fake_telegram.py "$port2" "$log" --refuse "chat not found" & server=$!
for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$port2/" && break; sleep 0.1; done
dir=$(post "{\"token\": \"1:S\", \"chatId\": \"0\", \"text\": \"x\", \"apiBase\": \"http://127.0.0.1:$port2\"}")
status "$dir" | grep -q error || fail "a refusal must fail the step"
grep -q "chat not found" "$dir/output.json" || fail "the reason is missing: $(cat "$dir/output.json")"

# opt out
dir=$(post "{\"token\": \"1:S\", \"chatId\": \"0\", \"text\": \"x\", \"apiBase\": \"http://127.0.0.1:$port2\", \"failOnError\": \"false\"}")
status "$dir" | grep -q success || fail "failOnError=false must not fail"
[ "$(output "$dir" sent)" = "false" ] || fail "sent flag should be false"

# missing inputs
dir=$(post "{\"chatId\": \"1\", \"text\": \"x\"}"); status "$dir" | grep -q error || fail "missing token must be refused"
dir=$(post "{\"token\": \"1:S\", \"text\": \"x\"}"); status "$dir" | grep -q error || fail "missing chatId must be refused"

echo "smoke ok: endpoint, body, token redaction, refusal, opt-out, missing inputs"
