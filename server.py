#!/usr/bin/env python3
"""考研打卡系统 - 局域网同步与静态服务
用法: PORT=8765 python3 server.py
- 静态托管本目录（手机/电脑浏览器访问 index.html）
- GET  /api/state  读取打卡数据
- PUT  /api/state  写入打卡数据（写入前自动备份 state.json.bak）
"""
import json
import os
import shutil
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

BASE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(BASE, 'state.json')
PORT = int(os.environ.get('PORT', '8765'))


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=BASE, **k)

    def log_message(self, fmt, *args):
        pass

    def _state(self):
        if not os.path.exists(STATE):
            return {'updatedAt': 0, 'state': None}
        try:
            with open(STATE, encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            shutil.copy(STATE, STATE + '.corrupt')
            return {'updatedAt': 0, 'state': None}

    def _json(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.split('?')[0] == '/api/state':
            self._json(self._state())
            return
        super().do_GET()

    def do_PUT(self):
        if self.path.split('?')[0] == '/api/state':
            n = int(self.headers.get('Content-Length') or 0)
            try:
                data = json.loads(self.rfile.read(n))
            except Exception:
                self.send_error(400)
                return
            if not isinstance(data, dict) or 'state' not in data:
                self.send_error(400)
                return
            if os.path.exists(STATE):
                shutil.copy(STATE, STATE + '.bak')
            with open(STATE, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False)
            self.send_response(204)
            self.end_headers()
            return
        self.send_error(404)

    do_POST = do_PUT


if __name__ == '__main__':
    srv = ThreadingHTTPServer(('0.0.0.0', PORT), Handler)
    print(f'考研打卡系统已启动: http://0.0.0.0:{PORT}  (目录: {BASE})', flush=True)
    srv.serve_forever()
