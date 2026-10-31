#!/usr/bin/env python3
"""A Telegram-shaped endpoint: records the request and answers like the Bot API.

    fake_telegram.py <port> <log> [--refuse 'description']
"""
import http.server
import json
import sys

port, log_path = int(sys.argv[1]), sys.argv[2]
refuse = ""
args = sys.argv[3:]
while args:
    if args.pop(0) == "--refuse":
        refuse = args.pop(0)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        payload = self.rfile.read(length).decode("utf-8") if length else ""
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({"path": self.path, "body": payload}) + "\n")
        if refuse:
            answer = {"ok": False, "error_code": 400, "description": refuse}
        else:
            answer = {"ok": True, "result": {"message_id": 4242, "chat": {"id": -100}}}
        raw = json.dumps(answer).encode("utf-8")
        self.send_response(200 if not refuse else 400)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
