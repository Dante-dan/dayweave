"""用 Google Cloud Speech-to-Text 转写探针样本（≤ 60 秒的同步识别）。

用法：python3 -I spikes/asr-eval/google_transcribe.py <wav>...
凭证从仓库根目录 .env 读取，不会打印：
- GOOGLE_SERVICE_ACCOUNT_FILE：服务账号 JSON 路径，v2（Chirp 3）必需；
- GOOGLE_SPEECH_API_KEY：API 密钥，只能用于 v1。
结果写在音频旁边：<name>.google-<model>.json / .txt
"""

import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# 项目 ID 从环境变量或服务账号 JSON 读取，不写进仓库。
PROJECT = os.environ.get("GOOGLE_CLOUD_PROJECT") or ""
ROOT = Path(__file__).resolve().parents[2]
# 中英双语：普通话为主，同时允许英语。
LANGUAGES = ["cmn-Hans-CN", "en-US"]


def env_value(name: str) -> str | None:
    env = ROOT / ".env"
    for line in env.read_text().splitlines() if env.exists() else []:
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip().strip('"') or None
    return None


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def service_account_token(path: str) -> str:
    """用服务账号私钥签 JWT 换访问令牌；签名交给系统 openssl，不引入第三方依赖。"""
    sa = json.loads(Path(path).expanduser().read_text())
    now = int(time.time())
    header = b64url(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64url(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/cloud-platform",
        "aud": sa["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing_input = f"{header}.{claims}".encode()
    with tempfile.NamedTemporaryFile("w", suffix=".pem") as pem:
        pem.write(sa["private_key"])
        pem.flush()
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", pem.name],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    assertion = f"{header}.{claims}.{b64url(signature)}"
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode()
    with urllib.request.urlopen(urllib.request.Request(sa["token_uri"], data=body), timeout=30) as resp:
        return json.load(resp)["access_token"]


def credentials() -> dict:
    """返回请求头里的认证字段。"""
    creds = {}
    if sa := env_value("GOOGLE_SERVICE_ACCOUNT_FILE"):
        creds["token"] = service_account_token(sa)
        creds["project"] = json.loads(Path(sa).expanduser().read_text())["project_id"]
    if key := env_value("GOOGLE_SPEECH_API_KEY"):
        creds["key"] = key
    if not creds:
        sys.exit("缺少凭证：请在 .env 中配置 GOOGLE_SERVICE_ACCOUNT_FILE 或 GOOGLE_SPEECH_API_KEY")
    return creds


def post(url: str, body: dict, creds: dict) -> dict:
    headers = {"Content-Type": "application/json"}
    if "token" in creds:
        headers["Authorization"] = f"Bearer {creds['token']}"
    elif "key" in creds:
        headers["X-Goog-Api-Key"] = creds["key"]
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"HTTP {e.code}: {e.read().decode(errors='replace')[:500]}") from None


def recognize_v2(audio: bytes, creds: dict) -> dict:
    """Chirp 3，多语言。"""
    url = f"https://us-speech.googleapis.com/v2/projects/{PROJECT or creds.get('project')}/locations/us/recognizers/_:recognize"
    body = {
        "config": {
            # 探针样本固定为 16 kHz 单声道 PCM。显式指定格式，探针被强制停止、文件头未回填时也能识别。
            "explicitDecodingConfig": {"encoding": "LINEAR16", "sampleRateHertz": 16000, "audioChannelCount": 1},
            "model": "chirp_3",
            "languageCodes": LANGUAGES,
            "features": {"enableAutomaticPunctuation": True},
        },
        "content": base64.b64encode(audio[44:]).decode(),
    }
    return post(url, body, creds)


def recognize_v1(audio: bytes, creds: dict) -> dict:
    url = "https://speech.googleapis.com/v1/speech:recognize"
    body = {
        "config": {
            "encoding": "LINEAR16",
            "sampleRateHertz": 16000,
            "languageCode": LANGUAGES[0],
            "alternativeLanguageCodes": LANGUAGES[1:],
            "enableAutomaticPunctuation": True,
            "model": "latest_long",
        },
        "audio": {"content": base64.b64encode(audio[44:]).decode()},
    }
    return post(url, body, creds)


def lines_from(result: dict) -> list[str]:
    out = []
    for r in result.get("results", []):
        alts = r.get("alternatives") or [{}]
        text = alts[0].get("transcript", "").strip()
        if text:
            lang = r.get("languageCode", "?")
            end = r.get("resultEndOffset") or r.get("resultEndTime") or ""
            conf = alts[0].get("confidence")
            out.append(f"[{end:>8}] ({lang}{'' if conf is None else f', conf={conf:.2f}'}) {text}")
    return out


def transcribe(path: Path, creds: dict) -> None:
    audio = path.read_bytes()
    for name, fn in (("chirp_3", recognize_v2), ("v1-latest_long", recognize_v1)):
        try:
            result = fn(audio, creds)
        except RuntimeError as e:
            print(f"  {name} 失败：{e}")
            continue
        stem = path.with_suffix("")
        Path(f"{stem}.google-{name}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
        lines = lines_from(result)
        Path(f"{stem}.google-{name}.txt").write_text("\n".join(lines) + "\n")
        print(f"== {path.name} [{name}]")
        print("\n".join(lines) if lines else "  （没有识别出语音）")
        return
    print(f"== {path.name}：所有模型都失败")


if __name__ == "__main__":
    c = credentials()
    for arg in sys.argv[1:]:
        transcribe(Path(arg), c)
