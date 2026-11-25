using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Glance;

/// Read-only TickTick, for focus time. Same route as Tickfind: TickTick's hosted MCP endpoint, signed in with OAuth
/// dynamic client registration and PKCE, so there's no client id or secret to set up. TickTick issues no refresh
/// token, so when the access token expires the focus chip asks you to sign in again.
public class TickTick
{
    const string Mcp = "https://mcp.ticktick.com/";
    const string Auth = "https://ticktick.com/oauth/authorize", TokenUrl = "https://api.ticktick.com/oauth/token",
        Register = "https://api.ticktick.com/oauth/register", Scope = "tasks:read";
    static readonly string TokenPath = System.IO.Path.Combine(AppContext.BaseDirectory, "glance-ticktick.dat");

    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(60) };
    string? token;
    DateTime expires = DateTime.MaxValue;

    public TickTick()
    {
        try
        {
            var j = JsonNode.Parse(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(TokenPath), null, DataProtectionScope.CurrentUser)))!;
            token = (string?)j["access"];
            if ((long?)j["expires"] is long e) expires = DateTimeOffset.FromUnixTimeSeconds(e).UtcDateTime;
        }
        catch { }
    }

    public bool SignedIn => token != null && DateTime.UtcNow < expires;

    void SignOut()
    {
        token = null;
        try { File.Delete(TokenPath); } catch { }
    }

    public async Task SignIn()
    {
        var verifier = B64(RandomNumberGenerator.GetBytes(32));
        var state = B64(RandomNumberGenerator.GetBytes(16));
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var redirect = $"http://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/callback";
            using var reg = await http.PostAsJsonAsync(Register, new
            {
                client_name = "Glance for Windows",
                redirect_uris = new[] { redirect },
                grant_types = new[] { "authorization_code" },
                response_types = new[] { "code" },
                token_endpoint_auth_method = "none",
                scope = Scope,
            });
            reg.EnsureSuccessStatusCode();
            var clientId = (string)JsonNode.Parse(await reg.Content.ReadAsStringAsync())!["client_id"]!;

            var url = Auth + "?" + Form(new()
            {
                ["client_id"] = clientId,
                ["redirect_uri"] = redirect,
                ["response_type"] = "code",
                ["scope"] = Scope,
                ["state"] = state,
                ["code_challenge"] = B64(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))),
                ["code_challenge_method"] = "S256",
                ["resource"] = Mcp,
            });
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

            using var r = await http.PostAsync(TokenUrl, new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "authorization_code",
                ["code"] = q["code"],
                ["redirect_uri"] = redirect,
                ["client_id"] = clientId,
                ["code_verifier"] = verifier,
                ["resource"] = Mcp,
            }));
            var body = await r.Content.ReadAsStringAsync();
            if (!r.IsSuccessStatusCode) throw new Exception($"TickTick refused the sign-in ({(int)r.StatusCode}): {body}");
            var tok = JsonNode.Parse(body)!;
            token = (string)tok["access_token"]!;
            expires = (long?)tok["expires_in"] is long secs ? DateTime.UtcNow.AddSeconds(secs - 300) : DateTime.MaxValue;
            var saved = new JsonObject { ["access"] = token, ["expires"] = expires == DateTime.MaxValue ? null : new DateTimeOffset(expires).ToUnixTimeSeconds() };
            File.WriteAllBytes(TokenPath, ProtectedData.Protect(Encoding.UTF8.GetBytes(saved.ToJsonString()), null, DataProtectionScope.CurrentUser));
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
                var line = await new StreamReader(stream).ReadLineAsync() ?? "";   // "GET /callback?code=..&state=.. HTTP/1.1"
                var path = line.Split(' ').ElementAtOrDefault(1) ?? "";
                var q = path.Contains('?')
                    ? path[(path.IndexOf('?') + 1)..].Split('&').Select(p => p.Split('=', 2))
                        .ToDictionary(p => p[0], p => Uri.UnescapeDataString(p.ElementAtOrDefault(1) ?? ""))
                    : new();
                var hit = q.ContainsKey("code") || q.ContainsKey("error");
                var msg = q.ContainsKey("code") ? "Glance is signed in to TickTick. You can close this tab." : "Sign-in didn't finish. Try again from Glance.";
                var body = hit ? $"<h2 style='font-family:sans-serif'>{msg}</h2>" : "";
                var resp = $"HTTP/1.1 {(hit ? "200 OK" : "404 Not Found")}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {Encoding.UTF8.GetByteCount(body)}\r\nConnection: close\r\n\r\n{body}";
                await stream.WriteAsync(Encoding.UTF8.GetBytes(resp));
                if (hit) result.TrySetResult(q);
            }
            catch { }
        }
    }

    /// TickTick's MCP answers "unknown_exception" or a 5xx for a few seconds at a time, so those get one retry.
    async Task<JsonNode?> Call(string tool, object args)
    {
        for (var attempt = 0; ; attempt++)
        {
            try { return await CallOnce(tool, args); }
            catch (Exception e) when (attempt == 0 && e is not NeedsSignIn && (e is HttpRequestException or TaskCanceledException || e.Message.Contains("unknown_exception")))
            {
                await Task.Delay(1500);
            }
        }
    }

    async Task<JsonNode?> CallOnce(string tool, object args)
    {
        if (!SignedIn) throw new NeedsSignIn();
        using var req = new HttpRequestMessage(HttpMethod.Post, Mcp)
        {
            Content = JsonContent.Create(new { jsonrpc = "2.0", id = 1, method = "tools/call", @params = new { name = tool, arguments = args } }),
        };
        req.Headers.Authorization = new("Bearer", token);
        req.Headers.Accept.ParseAdd("application/json");
        req.Headers.Accept.ParseAdd("text/event-stream");
        using var r = await http.SendAsync(req);
        if (r.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden) { SignOut(); throw new NeedsSignIn(); }
        r.EnsureSuccessStatusCode();
        var text = await r.Content.ReadAsStringAsync();
        // Streamable HTTP may answer as an event stream: the JSON is on the data: line.
        if (text.StartsWith("event:") || text.StartsWith("data:"))
            text = text.Split('\n').Last(l => l.StartsWith("data:"))[5..];
        var resp = JsonNode.Parse(text)!;
        var res = resp["result"];
        if (resp["error"] is { } err) throw new Exception($"{tool}: {err["message"] ?? err}");
        var first = (string?)res?["content"]?[0]?["text"];
        if ((bool?)res?["isError"] == true) throw new Exception($"{tool}: {first}");
        var sc = res?["structuredContent"];
        if (sc is JsonObject o) return o.TryGetPropertyValue("result", out var inner) ? inner : o;
        return first == null ? null : JsonNode.Parse(first);
    }

    /// Every focus record from [from] to now: pomodoros (type 0) and stopwatch (type 1). At most a month back.
    public async Task<List<JsonNode?>> Focuses(DateTime from)
    {
        var now = DateTimeOffset.Now;
        var both = await Task.WhenAll(new[] { 0, 1 }.Select(type =>
            Call("get_focuses_by_time", new { from_time = new DateTimeOffset(from).ToString("o"), to_time = now.ToString("o"), type })));
        return both.SelectMany(r => (r as JsonArray)?.ToList() ?? new()).ToList();
    }

    static string Form(Dictionary<string, string> kv) => string.Join("&", kv.Select(p => $"{p.Key}={Uri.EscapeDataString(p.Value)}"));
    static string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
}
