#!/usr/bin/env python3
"""Fake SliceOSM on the host, for tests that kill the app process.

An in-process fake (MockWebServer in AreaManagerTest) dies with the process,
so process-death tests talk to this server instead, through `adb reverse`.
It survives the kill and counts what each process asked for.

  POST /api/                 new job; the body is recorded; answers its UUID
  GET  /api/<job>            status: half done on the first poll, complete after
  GET  /files/<job>.osm.pbf  the current PBF (slowly in "slow" mode)
  GET  /basemap/{old,new}.pmtiles  distinguishable valid basemap snapshots
  POST /control              {"pbf": fixture name, "slow": bool}; answers the state
  GET  /state                {"submits", "polls", "downloads", "completed_downloads",
                              "jobs", "pbf", "slow"}

Usage: scripts/fake-sliceosm.py PORT FIXTURE_DIR
"""
import json
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PORT = int(sys.argv[1])
FIXTURES = Path(sys.argv[2])
TIMESTAMP = "2026-10-01T12:00:00Z"
# Slow mode sends this many bytes per second: the 441-byte fixture takes about
# a minute, ample time to kill the process mid-download.
SLOW_BYTES_PER_SECOND = 8

lock = threading.Lock()
state = {
    "submits": 0,
    "polls": 0,
    "downloads": 0,
    "completed_downloads": 0,
    "basemap_downloads": 0,
    "jobs": [],
    "pbf": "snapshot.osm.pbf",
    "slow": False,
}
polls_by_job = {}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        sys.stderr.write("fake-sliceosm: " + format % args + "\n")

    def reply(self, code, body, content_type="application/json"):
        data = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length).decode() if length else ""

    def do_POST(self):
        if self.path == "/api/":
            self.body()
            job = str(uuid.uuid4())
            with lock:
                state["submits"] += 1
                state["jobs"].append(job)
                polls_by_job[job] = 0
            self.reply(201, job + "\n", "text/plain")
        elif self.path == "/control":
            control = json.loads(self.body() or "{}")
            with lock:
                for key in ("pbf", "slow"):
                    if key in control:
                        state[key] = control[key]
                snapshot = json.dumps(state)
            self.reply(200, snapshot)
        else:
            self.reply(404, "not found", "text/plain")

    def do_GET(self):
        if self.path in ("/basemap/old.pmtiles", "/basemap/new.pmtiles"):
            data = (FIXTURES / "basemap/slc-nw-z12-15.pmtiles").read_bytes()
            # Unreferenced trailing bytes distinguish the replacement without
            # changing the archive's tile offsets or content.
            if self.path == "/basemap/new.pmtiles":
                data += b"refreshed"
            with lock:
                state["basemap_downloads"] += 1
            self.reply(200, data, "application/octet-stream")
            return
        if self.path == "/state":
            with lock:
                snapshot = json.dumps(state)
            self.reply(200, snapshot)
            return
        if self.path.startswith("/api/"):
            job = self.path[len("/api/"):]
            with lock:
                if job not in polls_by_job:
                    self.reply(404, "unknown job", "text/plain")
                    return
                state["polls"] += 1
                polls_by_job[job] += 1
                complete = polls_by_job[job] >= 2
                size = (FIXTURES / state["pbf"]).stat().st_size
            status = {
                "Timestamp": TIMESTAMP,
                "NodesTotal": 4,
                "NodesProg": 4 if complete else 2,
                "ElemsTotal": 8,
                "ElemsProg": 8 if complete else 4,
                "SizeBytes": size,
                "Complete": complete,
            }
            self.reply(200, json.dumps(status))
            return
        if self.path.startswith("/files/") and self.path.endswith(".osm.pbf"):
            job = self.path[len("/files/"):-len(".osm.pbf")]
            with lock:
                known = job in polls_by_job
                state["downloads"] += 1
                data = (FIXTURES / state["pbf"]).read_bytes()
                slow = state["slow"]
            if not known:
                self.reply(404, "unknown job", "text/plain")
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            try:
                step = SLOW_BYTES_PER_SECOND if slow else len(data)
                for start in range(0, len(data), step):
                    self.wfile.write(data[start:start + step])
                    self.wfile.flush()
                    if slow:
                        time.sleep(1)
            except (BrokenPipeError, ConnectionResetError):
                return  # the client died: the point of these tests
            with lock:
                state["completed_downloads"] += 1
            return
        self.reply(404, "not found", "text/plain")


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
