using System.IO;
using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Glance;

public class NeedsSignIn : Exception { }

/// Google Calendar over plain HTTP: installed-app OAuth (loopback + PKCE), read-only scope.
public class GoogleCal
{
    static string Dir => AppContext.BaseDirectory;
    static string TokenPath => Path.Combine(Dir, "token.dat");
    const string Api = "https://www.googleapis.com/calendar/v3/";

    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(20) };
    readonly string id = "", secret = "";
    string? refresh, access;
    DateTime accessExp;
    public string? ConfigError { get; }

    public GoogleCal()
    {
        try
        {
            // Your own client, if there's one: the JSON Google Cloud Console downloads ({"installed": {...}}) or a flat one.
            var j = JsonNode.Parse(File.ReadAllText(Path.Combine(Dir, "client.json")))!;
            var c = j["installed"] ?? j;
            id = (string)c["client_id"]!;
            secret = (string)c["client_secret"]!;
        }
        catch
        {
            // Else the one built into release builds (see Glance.csproj).
            string? Meta(string key) => typeof(GoogleCal).Assembly.GetCustomAttributes(typeof(System.Reflection.AssemblyMetadataAttribute), false)
                .Cast<System.Reflection.AssemblyMetadataAttribute>().FirstOrDefault(a => a.Key == key)?.Value;
            if (Meta("GoogleClientId") is { Length: > 0 } builtId && Meta("GoogleClientSecret") is { Length: > 0 } builtSecret) (id, secret) = (builtId, builtSecret);
            else ConfigError = "Put client.json (Google OAuth desktop client) next to Glance.exe";
        }
        try { refresh = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(TokenPath), null, DataProtectionScope.CurrentUser)); }
        catch { }
    }

    public bool SignedIn => refresh != null;

    public void SignOut()
    {
        refresh = access = null;
        File.Delete(TokenPath);
    }

    public async Task SignIn()
    {
        var verifier = B64(RandomNumberGenerator.GetBytes(32));
        var state = B64(RandomNumberGenerator.GetBytes(16));
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var redirect = $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/";
            var url = "https://accounts.google.com/o/oauth2/v2/auth?" + string.Join("&", new Dictionary<string, string>
            {
                ["client_id"] = id,
                ["redirect_uri"] = redirect,
                ["response_type"] = "code",
                ["scope"] = "https://www.googleapis.com/auth/calendar.readonly",
                ["code_challenge"] = B64(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))),
                ["code_challenge_method"] = "S256",
                ["state"] = state,
                ["access_type"] = "offline",
                ["prompt"] = "select_account consent",   // always show the account chooser, even if the browser is signed in to one account
            }.Select(kv => $"{kv.Key}={Uri.EscapeDataString(kv.Value)}"));
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

            // Browsers open speculative connections that never send a request, so serve every
            // connection concurrently and take the first one that carries the redirect.
            var result = new TaskCompletionSource<Dictionary<string, string>>();
            _ = Task.Run(async () =>
            {
                while (!result.Task.IsCompleted)
                {
                    TcpClient c;
                    try { c = await listener.AcceptTcpClientAsync(); } catch { return; }
                    _ = Serve(c, result);
                }
            });
            var q = await result.Task.WaitAsync(TimeSpan.FromMinutes(15));
            if (q.TryGetValue("error", out var err)) throw new Exception(err);
            if (q.GetValueOrDefault("state") != state) throw new Exception("state mismatch");

            var tok = await PostToken(new()
            {
                ["code"] = q["code"],
                ["client_id"] = id,
                ["client_secret"] = secret,
                ["redirect_uri"] = redirect,
                ["grant_type"] = "authorization_code",
                ["code_verifier"] = verifier,
            });
            refresh = (string?)tok["refresh_token"] ?? throw new Exception("Google returned no refresh token");
            Take(tok);
            File.WriteAllBytes(TokenPath, ProtectedData.Protect(Encoding.UTF8.GetBytes(refresh), null, DataProtectionScope.CurrentUser));
        }
        finally { listener.Stop(); }
    }

    static async Task Serve(TcpClient c, TaskCompletionSource<Dictionary<string, string>> result)
    {
        using (c)
        {
            try
            {
                var stream = c.GetStream();
                var line = await new StreamReader(stream).ReadLineAsync() ?? "";   // "GET /?code=..&state=.. HTTP/1.1"
                var path = line.Split(' ').ElementAtOrDefault(1) ?? "";
                var q = path.Contains('?')
                    ? path[(path.IndexOf('?') + 1)..].Split('&').Select(p => p.Split('=', 2))
                        .ToDictionary(p => p[0], p => Uri.UnescapeDataString(p.ElementAtOrDefault(1) ?? ""))
                    : new();
                var hit = q.ContainsKey("code") || q.ContainsKey("error");
                var body = hit ? "<h2 style='font-family:sans-serif'>Glance is signed in. You can close this tab.</h2>" : "";
                var resp = $"HTTP/1.1 {(hit ? "200 OK" : "404 Not Found")}\r\nContent-Type: text/html\r\nContent-Length: {Encoding.UTF8.GetByteCount(body)}\r\nConnection: close\r\n\r\n{body}";
                await stream.WriteAsync(Encoding.UTF8.GetBytes(resp));
                if (hit) result.TrySetResult(q);
            }
            catch { }
        }
    }

    async Task<string> Token()
    {
        if (access != null && DateTime.UtcNow < accessExp) return access;
        if (refresh == null) throw new NeedsSignIn();
        try
        {
            Take(await PostToken(new()
            {
                ["refresh_token"] = refresh,
                ["client_id"] = id,
                ["client_secret"] = secret,
                ["grant_type"] = "refresh_token",
            }));
        }
        catch (HttpRequestException e) when (e.StatusCode is HttpStatusCode.BadRequest or HttpStatusCode.Unauthorized)
        {
            SignOut();   // revoked or expired grant
            throw new NeedsSignIn();
        }
        return access!;
    }

    void Take(JsonNode tok)
    {
        access = (string)tok["access_token"]!;
        accessExp = DateTime.UtcNow.AddSeconds((int)tok["expires_in"]! - 60);
    }

    async Task<JsonNode> PostToken(Dictionary<string, string> form)
    {
        using var r = await http.PostAsync("https://oauth2.googleapis.com/token", new FormUrlEncodedContent(form));
        r.EnsureSuccessStatusCode();
        return JsonNode.Parse(await r.Content.ReadAsStringAsync())!;
    }

    async Task<JsonNode> Get(string path)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, Api + path);
        req.Headers.Authorization = new("Bearer", await Token());
        using var r = await http.SendAsync(req);
        r.EnsureSuccessStatusCode();
        return JsonNode.Parse(await r.Content.ReadAsStringAsync())!;
    }

    string? account;   // the signed-in address (the primary calendar's id), so event links open in the right account

    public async Task<List<Cal>> Calendars()
    {
        var j = await Get("users/me/calendarList?maxResults=250");
        account = (string?)j["items"]!.AsArray().FirstOrDefault(c => (bool?)c!["primary"] == true)?["id"];
        return j["items"]!.AsArray().Select(c => new Cal(
            (string)c!["id"]!,
            (string?)c["summaryOverride"] ?? (string?)c["summary"] ?? "?",
            (string?)c["backgroundColor"] ?? "#4285F4",
            (bool?)c["selected"] ?? false,
            Popups(c["defaultReminders"]))).ToList();
    }

    public async Task<List<Ev>> Events(Cal cal, DateTime from, DateTime to)
    {
        static string T(DateTime d) => Uri.EscapeDataString(d.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"));
        var j = await Get($"calendars/{Uri.EscapeDataString(cal.Id)}/events?singleEvents=true&orderBy=startTime&maxResults=250&timeMin={T(from)}&timeMax={T(to)}");
        var list = new List<Ev>();
        foreach (var e in j["items"]!.AsArray())
        {
            if ((string?)e!["status"] == "cancelled" || (string?)e["eventType"] == "workingLocation") continue;
            if (e["attendees"]?.AsArray().Any(a => (bool?)a!["self"] == true && (string?)a["responseStatus"] == "declined") == true) continue;
            var allDay = e["start"]!["date"] != null;
            var r = e["reminders"];
            var reminders = (bool?)r?["useDefault"] == false ? Popups(r?["overrides"]) : cal.DefaultReminders;
            var link = (string?)e["htmlLink"];
            if (link != null && account != null) link += (link.Contains('?') ? "&" : "?") + "authuser=" + Uri.EscapeDataString(account);
            list.Add(new Ev((string?)e["summary"] ?? "(busy)", When(e["start"]!), When(e["end"]!), allDay, cal.Color, reminders, link, (string?)e["location"]));
        }
        return list;
    }

    /// Minutes-before for "popup" reminders (email reminders aren't ours to show).
    static int[] Popups(JsonNode? list) => list?.AsArray()
        .Where(x => (string?)x!["method"] == "popup")
        .Select(x => (int)x!["minutes"]!).ToArray() ?? [];

    static DateTime When(JsonNode n) => n["date"] is JsonNode d
        ? DateTime.ParseExact((string)d!, "yyyy-MM-dd", CultureInfo.InvariantCulture)
        : DateTimeOffset.Parse((string)n["dateTime"]!, CultureInfo.InvariantCulture).LocalDateTime;

    static string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
