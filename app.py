from __future__ import annotations

import base64
import json
import mimetypes
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

from core import ProjectError, decode_base64, export_package, plan_summary, read_waterway_litematic


ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"


class App(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: object) -> None:
        print("[web] " + fmt % args)

    def _json(self, status: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)

    def _payload(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/": path = "/index.html"
        target = (STATIC / path.lstrip("/")).resolve()
        if STATIC not in target.parents or not target.is_file():
            self.send_error(HTTPStatus.NOT_FOUND); return
        data = target.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", mimetypes.guess_type(target.name)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers(); self.wfile.write(data)

    def do_POST(self) -> None:
        try:
            data = self._payload()
            if self.path == "/api/parse-waterway":
                waterway = read_waterway_litematic(decode_base64(data["file"]), data.get("name", "水道投影"))
                self._json(200, {"name": waterway.name, "items": list(waterway.items), "containers": waterway.containers})
            elif self.path == "/api/plan":
                self._json(200, plan_summary(data["config"], data["waterways"]))
            elif self.path == "/api/export":
                package = export_package(data["config"], data["waterways"])
                self._json(200, {"filename": "encoder-project.zip", "data": base64.b64encode(package).decode("ascii")})
            else:
                self.send_error(HTTPStatus.NOT_FOUND)
        except (ProjectError, KeyError, ValueError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:  # pragma: no cover - protects the browser from a raw traceback
            self._json(500, {"error": f"服务器处理失败：{exc}"})


if __name__ == "__main__":
    address = ("127.0.0.1", 8766)
    print("全物品编码投影生成器已启动：http://127.0.0.1:8766")
    ThreadingHTTPServer(address, App).serve_forever()
