using System.Diagnostics;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using Microsoft.Toolkit.Uwp.Notifications;
using Microsoft.Win32;

namespace Sinx;

internal static class Program
{
    private const int Port = 8765;
    private static readonly AppState State = new();
    private static NotifyIcon? _tray;
    private static TcpListener? _listener;
    private static CancellationTokenSource? _cts;

    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);

        RegisterStartup();
        EnsureFirewallHintOnly();

        _cts = new CancellationTokenSource();
        StartServer(_cts.Token);

        _tray = BuildTrayIcon();
        ShowToast("Sinx", $"Receiver running on port {Port}");
        Application.Run(new ApplicationContext());

        _cts.Cancel();
        _listener?.Stop();
        _tray.Dispose();
    }

    private static NotifyIcon BuildTrayIcon()
    {
        var icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Application;
        var tray = new NotifyIcon
        {
            Icon = icon,
            Text = $"Sinx — :{Port}",
            Visible = true,
            ContextMenuStrip = BuildMenu()
        };
        return tray;
    }

    private static ContextMenuStrip BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Opening += (_, _) =>
        {
            menu.Items.Clear();
            menu.Items.Add(new ToolStripMenuItem("Sinx") { Enabled = false });
            menu.Items.Add(new ToolStripMenuItem($":{Port}") { Enabled = false });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add(new ToolStripMenuItem($"Received: {State.Received} notification(s)") { Enabled = false });
            menu.Items.Add(new ToolStripMenuItem(State.LastApp.Length > 0
                ? $"Last: {State.LastApp} — {State.LastTitle}"
                : "No notifications yet") { Enabled = false });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Exit", null, (_, _) => Application.Exit());
        };
        return menu;
    }

    private static void StartServer(CancellationToken token)
    {
        _listener = new TcpListener(IPAddress.Any, Port);
        _listener.Start();

        _ = Task.Run(async () =>
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var client = await _listener.AcceptTcpClientAsync(token).ConfigureAwait(false);
                    _ = Task.Run(() => HandleClient(client, token), token);
                }
                catch (OperationCanceledException) { break; }
                catch { await Task.Delay(500, token).ConfigureAwait(false); }
            }
        }, token);
    }

    private const int MaxHeaderBytes = 8 * 1024;
    private const int MaxBodyBytes = 64 * 1024;

    private static async Task HandleClient(TcpClient client, CancellationToken token)
    {
        using var _ = client;
        using var stream = client.GetStream();

        // Parse headers off raw bytes (no StreamReader) so the byte offset where
        // headers end is known exactly — StreamReader silently enforces a minimum
        // 128-byte internal buffer, which read-ahead past that boundary and swallowed
        // body bytes that a later raw stream.ReadAsync could never recover.
        var headerBuf = new List<byte>();
        byte[] chunk = new byte[1024];
        int headerEnd = -1;
        while (headerEnd < 0)
        {
            int n = await stream.ReadAsync(chunk, token).ConfigureAwait(false);
            if (n == 0) break;
            headerBuf.AddRange(chunk.AsSpan(0, n).ToArray());
            headerEnd = FindHeaderEnd(headerBuf);
            if (headerBuf.Count > MaxHeaderBytes)
            {
                await WriteResponse(stream, 400, token).ConfigureAwait(false);
                return;
            }
        }

        if (headerEnd < 0)
        {
            await WriteResponse(stream, 400, token).ConfigureAwait(false);
            return;
        }

        string headerText = Encoding.ASCII.GetString(headerBuf.ToArray(), 0, headerEnd);
        string[] lines = headerText.Split("\r\n", StringSplitOptions.RemoveEmptyEntries);

        if (lines.Length == 0 || !lines[0].StartsWith("POST /notify", StringComparison.OrdinalIgnoreCase))
        {
            await WriteResponse(stream, 404, token).ConfigureAwait(false);
            return;
        }

        int contentLength = 0;
        for (int i = 1; i < lines.Length; i++)
        {
            int colon = lines[i].IndexOf(':');
            if (colon <= 0) continue;
            string name = lines[i][..colon].Trim();
            string value = lines[i][(colon + 1)..].Trim();
            if (name.Equals("Content-Length", StringComparison.OrdinalIgnoreCase))
                int.TryParse(value, out contentLength);
        }

        if (contentLength <= 0 || contentLength > MaxBodyBytes)
        {
            await WriteResponse(stream, 400, token).ConfigureAwait(false);
            return;
        }

        // Content-Length is a byte count, so the body must be read as bytes (not
        // chars) and UTF-8 decoded afterward — otherwise multi-byte text (emoji,
        // accented characters, non-Latin scripts) desyncs the read loop and hangs.
        byte[] bodyBytes = new byte[contentLength];
        int bodyStart = headerEnd + 4; // past "\r\n\r\n"
        int alreadyHave = Math.Min(Math.Max(headerBuf.Count - bodyStart, 0), contentLength);
        if (alreadyHave > 0)
            headerBuf.CopyTo(bodyStart, bodyBytes, 0, alreadyHave);

        int read = alreadyHave;
        while (read < contentLength)
        {
            int n = await stream.ReadAsync(bodyBytes.AsMemory(read, contentLength - read), token).ConfigureAwait(false);
            if (n == 0) break;
            read += n;
        }

        try
        {
            using JsonDocument doc = JsonDocument.Parse(Encoding.UTF8.GetString(bodyBytes, 0, read));
            string app = GetString(doc, "app", "Unknown");
            string title = GetString(doc, "title", "");
            string text = GetString(doc, "text", "");

            State.Record(app, title.Length > 0 ? title : "(no title)");
            ShowNotification(app, title, text);
            await WriteResponse(stream, 200, token).ConfigureAwait(false);
        }
        catch
        {
            await WriteResponse(stream, 400, token).ConfigureAwait(false);
        }
    }

    /// Returns the index of the start of "\r\n\r\n" in buf, or -1 if not present.
    private static int FindHeaderEnd(List<byte> buf)
    {
        for (int i = 0; i + 3 < buf.Count; i++)
        {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n')
                return i;
        }
        return -1;
    }

    private static string GetString(JsonDocument doc, string name, string fallback)
    {
        return doc.RootElement.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? fallback
            : fallback;
    }

    private static void ShowNotification(string app, string title, string text)
    {
        string header = title.Length > 0 ? $"{app}: {title}" : app;
        string body = text.Length > 0 ? text : "(no body)";

        if (_tray is not null) _tray.Text = $"Sinx — {State.Received} received";
        ShowToast(header, body);
    }

    private static void ShowToast(string header, string body)
    {
        // Real WinRT toast, not NotifyIcon.ShowBalloonTip — balloon tips are dropped
        // entirely (and never logged) while Focus Assist/DND is on. Toasts still land
        // in Action Center under DND so missed notifications can be reviewed later.
        try
        {
            new ToastContentBuilder()
                .AddText(header)
                .AddText(body)
                .Show();
        }
        catch { /* non-fatal — notification just doesn't show */ }
    }

    private static async Task WriteResponse(NetworkStream stream, int code, CancellationToken token)
    {
        string reason = code switch { 200 => "OK", 400 => "Bad Request", 404 => "Not Found", _ => "Error" };
        byte[] bytes = Encoding.ASCII.GetBytes($"HTTP/1.1 {code} {reason}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        await stream.WriteAsync(bytes, token).ConfigureAwait(false);
    }

    private static void RegisterStartup()
    {
        try
        {
            using RegistryKey? key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            key?.SetValue("Sinx", $"\"{Application.ExecutablePath}\"");
        }
        catch { /* non-fatal */ }
    }

    private static void EnsureFirewallHintOnly()
    {
        // Firewall rule is already created by setup.ps1. Native app intentionally
        // does not elevate or mutate machine firewall state at runtime.
    }

    private sealed class AppState
    {
        private readonly object _lock = new();
        public int Received { get; private set; }
        public string LastApp { get; private set; } = "";
        public string LastTitle { get; private set; } = "";

        public void Record(string app, string title)
        {
            lock (_lock)
            {
                Received++;
                LastApp = app;
                LastTitle = title;
            }
        }
    }
}
