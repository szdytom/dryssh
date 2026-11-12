#!/usr/bin/env python3
"""Local fixed Responses fixture for exercising the real Codex terminal UI."""

import json
import time
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Backend(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        data = json.dumps(
            {
                "object": "list",
                "data": [
                    {"id": "dryssh-fixture", "object": "model", "owned_by": "fixture"}
                ],
            }
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        users = [item for item in request["input"] if item.get("role") == "user"]
        text = "".join(
            part["text"]
            for part in users[-1]["content"]
            if part["type"] == "input_text"
        )
        digest = sha256(text.encode()).hexdigest()[:12]
        Path(f"/tmp/dryssh-codex-{digest}.txt").write_text(text)
        reply = f"https://s/codex-{digest}"
        response = {
            "id": f"resp_{digest}",
            "object": "response",
            "created_at": int(time.time()),
            "model": "dryssh-fixture",
            "status": "in_progress",
            "output": [],
        }
        part = {"type": "output_text", "text": reply, "annotations": []}
        item = {
            "id": f"msg_{digest}",
            "type": "message",
            "role": "assistant",
            "status": "completed",
            "content": [part],
        }
        events = [
            {"type": "response.created", "response": response.copy()},
            {
                "type": "response.output_item.added",
                "output_index": 0,
                "item": {**item, "status": "in_progress", "content": []},
            },
            {
                "type": "response.content_part.added",
                "output_index": 0,
                "item_id": item["id"],
                "content_index": 0,
                "part": {**part, "text": ""},
            },
            {
                "type": "response.output_text.delta",
                "output_index": 0,
                "item_id": item["id"],
                "content_index": 0,
                "delta": reply,
            },
            {
                "type": "response.output_text.done",
                "output_index": 0,
                "item_id": item["id"],
                "content_index": 0,
                "text": reply,
            },
            {"type": "response.output_item.done", "output_index": 0, "item": item},
            {
                "type": "response.completed",
                "response": {
                    **response,
                    "status": "completed",
                    "output": [item],
                    "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2},
                },
            },
        ]
        data = "".join(
            f"event: {event['type']}\ndata: {json.dumps({**event, 'sequence_number': i})}\n\n"
            for i, event in enumerate(events)
        ).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 3333), Backend).serve_forever()
