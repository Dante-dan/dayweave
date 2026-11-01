#!/usr/bin/env python3
"""
拾日后台（dayweave.duanjl.com/admin）。只用 Python 标准库，跑在落地页那台机器上，只监听本机，由 nginx + Cloudflare Tunnel 转进来。

- 登录：一个超长密码（scrypt 哈希存在 config.json，明文不落盘）。按来源 IP 计失败次数，超过就指数级加长锁定时间。
- 会话：随机令牌放在 HttpOnly + Secure + SameSite=Strict 的 Cookie 里；接口还要求自定义请求头，挡住跨站请求。
- 数据：转发到拾日云的 /v1/admin/*（Bearer ADMIN_TOKEN）。授权码明文只在浏览器里生成和显示，这里只经手哈希。

用法：
  python3 server.py serve            # 启动
  python3 server.py reset            # 生成新密码（128 位），打印一次；所有已登录的会话立即失效
  python3 server.py set-hash <hash>  # 写入在别处生成的密码哈希
  python3 server.py hash             # 从标准输入读密码，输出哈希
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import string
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.environ.get("DAYWEAVE_ADMIN_HOME", HERE)
CONFIG = os.path.join(STATE, "config.json")
LOCKOUT = os.path.join(STATE, "lockout.json")
WWW = os.path.join(HERE, "www")
PORT = int(os.environ.get("DAYWEAVE_ADMIN_PORT", "8791"))

PASSWORD_LENGTH = 128
SESSION_IDLE = 2 * 3600
SESSION_MAX = 12 * 3600
# 失败 3 次后开始锁：1 分钟起，每多错一次翻倍，最长 24 小时；24 小时内没再错就清零。
FREE_FAILURES = 3
LOCK_BASE = 60
LOCK_MAX = 24 * 3600
FAILURE_MEMORY = 24 * 3600

SCRYPT = dict(n=2 ** 15, r=8, p=1, maxmem=64 * 1024 * 1024, dklen=64)

# 允许转发的拾日云后台接口
API_ROUTES = {
    ("GET", "overview"), ("GET", "licenses"), ("GET", "license"),
    ("POST", "licenses"), ("POST", "license"),
}

lock = threading.Lock()
sessions = {}  # token -> {created, seen, epoch, ip}


# ---------- 密码 ----------

def generate_password(length=PASSWORD_LENGTH):
    """128 位，取自全部 94 个可见 ASCII 字符，保证四类字符都有。"""
    classes = [string.ascii_lowercase, string.ascii_uppercase, string.digits, string.punctuation]
    alphabet = "".join(classes)
    while True:
        pw = "".join(secrets.choice(alphabet) for _ in range(length))
        if all(any(c in cls for c in pw) for cls in classes):
            return pw


def hash_password(password):
    salt = secrets.token_bytes(32)
    dk = hashlib.scrypt(password.encode(), salt=salt, **SCRYPT)
    return "scrypt$%d$%d$%d$%s$%s" % (SCRYPT["n"], SCRYPT["r"], SCRYPT["p"], base64.b64encode(salt).decode(), base64.b64encode(dk).decode())


def verify_password(password, stored):
    try:
        _, n, r, p, salt, dk = stored.split("$")
        got = hashlib.scrypt(password.encode()[:1024], salt=base64.b64decode(salt), n=int(n), r=int(r), p=int(p),
                             maxmem=SCRYPT["maxmem"], dklen=len(base64.b64decode(dk)))
        return hmac.compare_digest(got, base64.b64decode(dk))
    except Exception:
        return False


# ---------- 配置与锁定状态 ----------

def write_private(path, data):
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)


def read_json(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        return default


def config():
    return read_json(CONFIG, {})


def save_password_hash(h):
    cfg = config()
    cfg["password_hash"] = h
    cfg["epoch"] = secrets.token_hex(8)  # 换密码后所有旧会话失效
    cfg["password_changed_at"] = int(time.time())
    write_private(CONFIG, cfg)


def lockout_state():
    return read_json(LOCKOUT, {})


def locked_for(ip):
    """返回还要锁多少秒（0 表示可以尝试）。"""
    entry = lockout_state().get(ip)
    if not entry:
        return 0
    return max(0, int(entry.get("until", 0) - time.time()))


def record_failure(ip):
    with lock:
        state = lockout_state()
        now = time.time()
        # 清理过期记录
        state = {k: v for k, v in state.items() if now - v.get("last", 0) < FAILURE_MEMORY}
        entry = state.get(ip, {"count": 0})
        entry["count"] = entry.get("count", 0) + 1
        entry["last"] = now
        over = entry["count"] - FREE_FAILURES
        entry["until"] = now + min(LOCK_MAX, LOCK_BASE * (2 ** (over - 1))) if over > 0 else 0
        state[ip] = entry
        write_private(LOCKOUT, state)
        return int(entry["until"] - now) if entry["until"] else 0


def clear_failures(ip):
    with lock:
        state = lockout_state()
        if state.pop(ip, None) is not None:
            write_private(LOCKOUT, state)


# ---------- 会话 ----------

def new_session(ip):
    token = secrets.token_urlsafe(32)
    now = time.time()
    with lock:
        sessions[token] = {"created": now, "seen": now, "epoch": config().get("epoch"), "ip": ip}
    return token


def check_session(token):
    if not token:
        return False
    now = time.time()
    epoch = config().get("epoch")
    with lock:
        s = sessions.get(token)
        if not s:
            return False
        if s["epoch"] != epoch or now - s["seen"] > SESSION_IDLE or now - s["created"] > SESSION_MAX:
            sessions.pop(token, None)
            return False
        s["seen"] = now
        return True


# ---------- HTTP ----------

STATIC = {
    "/admin": ("index.html", "text/html; charset=utf-8"),
    "/admin/": ("index.html", "text/html; charset=utf-8"),
    "/admin/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/admin/admin.css": ("admin.css", "text/css; charset=utf-8"),
}

SECURITY_HEADERS = {
    "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
                               "base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Cache-Control": "no-store",
    "X-Robots-Tag": "noindex, nofollow",
}


class Handler(BaseHTTPRequestHandler):
    server_version = "dayweave-admin"
    sys_version = ""

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.client_ip(), fmt % args))

    def client_ip(self):
        # 只能经由 Cloudflare Tunnel → nginx 进来，CF-Connecting-IP 由 Cloudflare 填写
        return self.headers.get("CF-Connecting-IP") or self.headers.get("X-Real-IP") or self.client_address[0]

    def cookie(self, name):
        for part in (self.headers.get("Cookie") or "").split(";"):
            k, _, v = part.strip().partition("=")
            if k == name:
                return v
        return None

    def send(self, status, body, ctype="application/json; charset=utf-8", extra=None):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for k, v in SECURITY_HEADERS.items():
            self.send_header(k, v)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def body(self, limit=1024 * 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("body too large")
        raw = self.rfile.read(n) if n else b""
        return json.loads(raw or b"{}")

    def api_guard(self):
        """接口必须带自定义头（跨站页面发不出来）且是 JSON。"""
        return self.headers.get("X-Dayweave-Admin") == "1"

    def authed(self):
        return check_session(self.cookie("dwadmin"))

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in STATIC:
            name, ctype = STATIC[path]
            with open(os.path.join(WWW, name), "rb") as f:
                return self.send(200, f.read(), ctype)
        if path == "/admin/api/session":
            return self.send(200, {"authed": self.authed()})
        if path.startswith("/admin/api/"):
            return self.proxy("GET")
        self.send(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if not self.api_guard():
            return self.send(403, {"error": "forbidden"})
        if path == "/admin/api/login":
            return self.login()
        if path == "/admin/api/logout":
            with lock:
                sessions.pop(self.cookie("dwadmin"), None)
            return self.send(200, {"ok": True}, extra={"Set-Cookie": "dwadmin=; Path=/admin; Max-Age=0; HttpOnly; Secure; SameSite=Strict"})
        if path.startswith("/admin/api/"):
            return self.proxy("POST")
        self.send(404, {"error": "not found"})

    def login(self):
        ip = self.client_ip()
        wait = locked_for(ip)
        if wait:
            # 锁定期间不校验密码，直接拒绝
            return self.send(429, {"error": "locked", "retryAfter": wait}, extra={"Retry-After": str(wait)})
        try:
            password = str(self.body(8 * 1024).get("password") or "")
        except ValueError:
            return self.send(400, {"error": "bad request"})
        stored = config().get("password_hash")
        if stored and len(password) == PASSWORD_LENGTH and verify_password(password, stored):
            clear_failures(ip)
            token = new_session(ip)
            return self.send(200, {"ok": True}, extra={
                "Set-Cookie": "dwadmin=%s; Path=/admin; Max-Age=%d; HttpOnly; Secure; SameSite=Strict" % (token, SESSION_MAX)})
        if not stored or len(password) != PASSWORD_LENGTH:
            # 长度不对也做一次哈希，避免靠响应时间区分
            verify_password(password, hash_password("x"))
        wait = record_failure(ip)
        self.send(401, {"error": "wrong password", "retryAfter": wait})

    def proxy(self, method):
        if not self.authed():
            return self.send(401, {"error": "login required"})
        if method == "GET" and not self.api_guard():
            return self.send(403, {"error": "forbidden"})
        path, _, query = self.path.partition("?")
        name = path[len("/admin/api/"):]
        if (method, name) not in API_ROUTES:
            return self.send(404, {"error": "not found"})
        cfg = config()
        url = cfg["api"].rstrip("/") + "/v1/admin/" + name + ("?" + query if query else "")
        data = None
        if method == "POST":
            try:
                data = json.dumps(self.body()).encode()
            except ValueError:
                return self.send(400, {"error": "bad request"})
        req = urllib.request.Request(url, data=data, method=method, headers={
            "Authorization": "Bearer " + cfg["admin_token"],
            "Content-Type": "application/json",
            "User-Agent": "dayweave-admin",
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                return self.send(res.status, res.read())
        except urllib.error.HTTPError as e:
            return self.send(e.code, e.read() or b"{}")
        except Exception as e:  # 网络错误
            return self.send(502, {"error": "upstream unavailable: %s" % e.__class__.__name__})


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if cmd == "reset":
        pw = generate_password()
        save_password_hash(hash_password(pw))
        with lock:
            write_private(LOCKOUT, {})
        print(pw)
    elif cmd == "set-hash":
        save_password_hash(sys.argv[2])
        print("ok")
    elif cmd == "hash":
        print(hash_password(sys.stdin.read().rstrip("\n")))
    elif cmd == "serve":
        if not config().get("admin_token") or not config().get("api"):
            sys.exit("config.json needs api and admin_token")
        ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
