"""Run with Python 3. The emulator reaches this server at 10.0.2.2:8765."""
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class DemoApi(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/suggestion":
            self.send_error(404)
            return
        time.sleep(6)
        body = json.dumps({"message": "Hôm nay bạn muốn ghi nhớ điều gì?"}, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 8765), DemoApi)
    print("Demo API http://127.0.0.1:8765/suggestion (6 seconds)", flush=True)
    server.serve_forever()
