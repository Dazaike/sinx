# Sinx — Phone-to-PC Notification Bridge

Sinx forwards Android notifications to your Windows 11 desktop over your local LAN using a lightweight HTTP connection. No cloud relay, no external accounts.

```
┌─────────────────┐   HTTP POST    ┌────────────────────────┐
│  Android phone  │ ─────────────▶ │  Windows 11 PC         │
│  (Sinx app)     │  :8765/notify  │  server.py + win11toast│
└─────────────────┘                └────────────────────────┘
       same LAN — no internet required
```

---

## Project layout

```
Sinx/
├── app/                          Android app (Kotlin)
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/sinx/notifbridge/
│           ├── MainActivity.kt         UI + permission gate
│           ├── NotificationListener.kt Intercepts all notifications
│           ├── KeepAliveService.kt     Foreground service (prevents kill)
│           ├── BootReceiver.kt         Auto-starts after reboot
│           ├── PcSender.kt             OkHttp POST to PC
│           └── SettingsManager.kt      Persists IP / port / blocklist
├── windows/
│   ├── server.py                 Python HTTP receiver → Windows toast
│   ├── requirements.txt
│   └── startup.bat               Double-click launcher / Task Scheduler target
├── gradle/libs.versions.toml
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Android setup

### 1. Build and install the APK

Open the project root in **Android Studio** (Hedgehog or newer) and click **Run**.  
Minimum Android version: **8.0 (API 26)**.

### 2. Grant notification access

On first launch, tap **"Grant Notification Access"** and toggle **Sinx** ON in the system settings screen.

### 3. Configure the PC target

Enter your PC's local IPv4 (find it with `ipconfig` → look for your Ethernet/Wi-Fi adapter) and the port (default `8765`).  
Tap **Save Target**.

### 4. Disable battery optimization for Sinx

```
Settings → Battery → Battery usage → Sinx → Unrestricted
```

Without this, Samsung/OnePlus/Xiaomi devices kill the foreground service overnight.

---

## Windows setup

### Prerequisites

Install the [.NET 9 Desktop Runtime (x64)](https://dotnet.microsoft.com/download/dotnet/9.0) on the target PC (one-time).

### 1. Build / run the native receiver

The Windows receiver is now a native tray app:

```powershell
dotnet publish windows\native\Sinx.csproj `
  -c Release `
  -r win-x64 `
  --self-contained false `
  -p:PublishSingleFile=true `
  -p:DebugType=none `
  -p:PublishDir=windows\dist-native\
```

Start it:

```powershell
windows\dist-native\Sinx.exe
```

Or double-click `windows\startup.bat`.

### 2. Firewall

The existing firewall rule is still used:

```powershell
New-NetFirewallRule `
    -DisplayName "Sinx Receiver" `
    -Direction Inbound `
    -Protocol TCP `
    -LocalPort 8765 `
    -Action Allow
```

### 3. Auto-start

`Sinx.exe` registers itself under:

```text
HKCU\Software\Microsoft\Windows\CurrentVersion\Run\Sinx
```

No Task Scheduler task is required.

---

## Finding your PC's local IP

```powershell
ipconfig
```

Look for the adapter you use for your home network (e.g. **Killer E3100G** or **Wi-Fi**):

```
IPv4 Address. . . . . . . . . . . : 192.168.1.42
```

Enter `192.168.1.42` in the Sinx app and save.

---

## Filtering noisy apps

In the Sinx app tap **"Manage Blocked Apps"** and enter one package name per line, e.g.:

```
com.android.systemui
com.google.android.gms
com.samsung.android.app.cocktailbarservice
```

The app hard-blocks `com.sinx.notifbridge` itself so you never get a forwarding loop.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| No toasts appear on PC | Check that `server.py` is running and port 8765 is open in the firewall |
| "Delivery failed" in logcat | Wrong IP or port — re-check `ipconfig` and the app's saved IP |
| Service keeps dying overnight | Set battery to **Unrestricted** in Android settings |
| Duplicate toasts | Group-summary and ongoing-event flags are already filtered; check logcat for the package name and add it to the blocklist |
| win11toast ImportError | `pip install win11toast` — requires Python 3.8+ |

---

## Version

`1.0.0`
