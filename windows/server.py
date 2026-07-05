"""
Sinx Windows Receiver v1.4.0

Distributed as a standalone Sinx.exe built with PyInstaller.
- No Python install required on the target machine
- Registers itself in HKCU Run at first launch (no Task Scheduler)
- System tray icon; HTTP server on a daemon thread
- AppUserModelID set so toasts show "Sinx" not Python
"""
from __future__ import annotations

import ctypes
import json
import logging
import sys
import threading
import winreg
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import pystray
from PIL import Image
from win11toast import notify

# ── Paths — work both as .py and as PyInstaller frozen .exe ──────────────────

if getattr(sys, "frozen", False):
    # Running as compiled exe: sit next to the exe file
    SCRIPT_DIR  = Path(sys.executable).parent
    BUNDLE_DIR  = Path(sys._MEIPASS)          # bundled resources (icon)
    EXE_PATH    = Path(sys.executable)
else:
    SCRIPT_DIR  = Path(__file__).parent
    BUNDLE_DIR  = SCRIPT_DIR
    EXE_PATH    = None

# Icon: prefer one sitting next to the exe (user can replace it), fall back to bundle
ICON_PATH = SCRIPT_DIR / "icon.png"
if not ICON_PATH.exists():
    ICON_PATH = BUNDLE_DIR / "icon.png"

# ── Config ────────────────────────────────────────────────────────────────────

HOST   = "0.0.0.0"
PORT   = 8765
APP_ID = "Sinx.NotificationBridge"

# ── Logging — file only (no console under --windowed) ────────────────────────

logging.basicConfig(
    level    = logging.INFO,
    format   = "%(asctime)s  %(levelname)-7s  %(message)s",
    datefmt  = "%H:%M:%S",
    handlers = [logging.FileHandler(SCRIPT_DIR / "sinx.log", encoding="utf-8")],
)
log = logging.getLogger("sinx")

# ── Shared state ──────────────────────────────────────────────────────────────

@dataclass
class _State:
    received:   int = 0
    last_app:   str = ""
    last_title: str = ""
    _lock: object   = None

    def __post_init__(self):
        self._lock = threading.Lock()

    def record(self, app: str, title: str) -> None:
        with self._lock:
            self.received  += 1
            self.last_app   = app
            self.last_title = title

state = _State()

# ── Windows identity ──────────────────────────────────────────────────────────

def _brand_process() -> None:
    """
    1. SetCurrentProcessExplicitAppUserModelID — brands this process as Sinx
       at the OS level so Windows toast headers show "Sinx" not Python.
    2. HKCU registry entry — supplies DisplayName + icon path for the toast UI.
    """
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_ID)
    except Exception as exc:
        log.warning("SetCurrentProcessExplicitAppUserModelID: %s", exc)

    key_path = f"Software\\Classes\\AppUserModelId\\{APP_ID}"
    try:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, key_path) as key:
            winreg.SetValueEx(key, "DisplayName", 0, winreg.REG_SZ, "Sinx")
            if ICON_PATH.exists():
                winreg.SetValueEx(key, "IconUri", 0, winreg.REG_SZ, str(ICON_PATH))
    except OSError as exc:
        log.warning("AppUserModelId registry: %s", exc)


def _register_startup() -> None:
    """
    Add Sinx.exe to HKCU\\…\\Run so it starts at every login.
    Only runs when frozen (compiled exe) — skip when running as a plain .py.
    """
    if EXE_PATH is None:
        return
    run_key = r"Software\Microsoft\Windows\CurrentVersion\Run"
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, run_key, 0,
                            winreg.KEY_SET_VALUE) as key:
            winreg.SetValueEx(key, "Sinx", 0, winreg.REG_SZ, f'"{EXE_PATH}"')
        log.info("Startup registered: %s", EXE_PATH)
    except OSError as exc:
        log.warning("Startup registry: %s", exc)

# ── HTTP handler ──────────────────────────────────────────────────────────────

class _NotifyHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        pass

    def do_POST(self) -> None:
        if self.path != "/notify":
            self._respond(404); return

        length = int(self.headers.get("Content-Length", 0))
        if not length:
            self._respond(400); return

        try:
            data: dict = json.loads(self.rfile.read(length))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            log.warning("Bad payload: %s", exc); self._respond(400); return

        app   = data.get("app",   "Unknown").strip()
        title = data.get("title", "").strip()
        text  = data.get("text",  "").strip()

        state.record(app, title or "(no title)")
        log.info("[%s] %s — %s", app, title or "(no title)", text or "(no text)")

        try:
            notify(
                title    = f"{app}: {title}" if title else app,
                body     = text or "(no body)",
                app_id   = APP_ID,
                duration = "short",
            )
        except Exception as exc:
            log.error("Toast failed: %s", exc)

        self._respond(200)

    def _respond(self, code: int) -> None:
        self.send_response(code)
        self.send_header("Content-Length", "0")
        self.end_headers()

# ── Tray ──────────────────────────────────────────────────────────────────────

def _load_icon() -> Image.Image:
    if ICON_PATH.exists():
        return Image.open(ICON_PATH).convert("RGBA").resize((64, 64), Image.LANCZOS)
    return Image.new("RGBA", (64, 64), (0x3D, 0x5A, 0xFE, 255))


def _make_menu(httpd: HTTPServer) -> pystray.Menu:
    def _count(item):
        return f"Received: {state.received} notification(s)"

    def _last(item):
        return f"Last: {state.last_app} — {state.last_title}" \
               if state.last_app else "No notifications yet"

    def _exit(icon, item):
        icon.stop()
        httpd.shutdown()

    return pystray.Menu(
        pystray.MenuItem("Sinx",         None,    enabled=False),
        pystray.MenuItem(f":{PORT}",     None,    enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem(_count,         None,    enabled=False),
        pystray.MenuItem(_last,          None,    enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Exit",         _exit),
    )

# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    _brand_process()
    _register_startup()

    httpd  = HTTPServer((HOST, PORT), _NotifyHandler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True, name="sinx-http")
    thread.start()
    log.info("Sinx.exe listening on %s:%d", HOST, PORT)

    icon = pystray.Icon("Sinx", _load_icon(), f"Sinx — :{PORT}", _make_menu(httpd))
    icon.run()

    httpd.shutdown()
    log.info("Sinx stopped.")


if __name__ == "__main__":
    main()
