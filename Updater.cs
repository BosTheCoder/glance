using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Windows;

namespace Glance;

/// Updates from the GitHub releases: the same exe you'd download by hand, swapped in place.
static class Updater
{
    const string Api = "https://api.github.com/repos/BosTheCoder/glance/releases/latest";

    public record Release(Version Version, string Tag, string Url, string? Sha256);

    public static Version Current
    {
        get { var v = typeof(Updater).Assembly.GetName().Version!; return new(v.Major, v.Minor, Math.Max(0, v.Build)); }
    }

    static HttpClient Http()
    {
        var h = new HttpClient();
        h.DefaultRequestHeaders.UserAgent.ParseAdd("Glance-updater");   // GitHub's API rejects requests without one
        return h;
    }

    /// The latest release if it's newer than this build and has our exe, else null.
    public static async Task<Release?> Newer()
    {
        using var http = Http();
        var j = JsonNode.Parse(await http.GetStringAsync(Api))!;
        var tag = (string)j["tag_name"]!;
        if (!Version.TryParse(tag.TrimStart('v'), out var v) || new Version(v.Major, v.Minor, Math.Max(0, v.Build)) <= Current) return null;
        // The standalone build is ~70 MB and the framework-dependent one well under 1 MB; update to the same kind.
        var name = new FileInfo(Environment.ProcessPath!).Length > 20_000_000 ? "Glance-standalone.exe" : "Glance.exe";
        var asset = j["assets"]!.AsArray().FirstOrDefault(a => (string?)a!["name"] == name);
        if (asset == null) return null;
        var digest = (string?)asset["digest"];   // "sha256:…" on current GitHub releases
        return new(v, tag, (string)asset["browser_download_url"]!, digest?.StartsWith("sha256:") == true ? digest[7..] : null);
    }

    /// Download next to the running exe, swap it in (a running exe can be renamed but not overwritten) and restart.
    public static async Task Install(Release r)
    {
        var exe = Environment.ProcessPath!;
        using (var http = Http())
        {
            var bytes = await http.GetByteArrayAsync(r.Url);
            if (r.Sha256 != null && !Convert.ToHexString(SHA256.HashData(bytes)).Equals(r.Sha256, StringComparison.OrdinalIgnoreCase))
                throw new Exception("the download didn't match GitHub's checksum");
            await File.WriteAllBytesAsync(exe + ".new", bytes);
        }
        File.Delete(exe + ".old");
        File.Move(exe, exe + ".old");
        File.Move(exe + ".new", exe);
        var args = Environment.GetCommandLineArgs().Skip(1).Where(a => a != "--updated").Append("--updated");
        Process.Start(new ProcessStartInfo(exe, string.Join(' ', args)));   // waits for this instance to exit (see App)
        Application.Current.Shutdown();
    }

    /// The previous exe can only be deleted once it's no longer running, so the new one tidies up at start.
    public static void CleanUp()
    {
        try { File.Delete(Environment.ProcessPath + ".old"); } catch { }
    }
}
