# Capture the Glance window (plus a margin) to a PNG: used to refresh docs/ screenshots.
#   powershell -File tools\screenshot.ps1 out.png [margin]
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  public struct R { public int L, T, Rt, B; }
}
"@
[W]::SetProcessDPIAware() | Out-Null
$h = [W]::FindWindow([NullString]::Value, "Glance")
if ($h -eq [IntPtr]::Zero) { throw "Glance window not found" }
$r = New-Object W+R; [W]::GetWindowRect($h, [ref]$r) | Out-Null
$m = if ($args.Count -gt 1) { [int]$args[1] } else { 0 }
$w = $r.Rt - $r.L + 2 * $m; $hh = $r.B - $r.T + 2 * $m
$bmp = New-Object Drawing.Bitmap $w, $hh
[Drawing.Graphics]::FromImage($bmp).CopyFromScreen($r.L - $m, $r.T - $m, 0, 0, $bmp.Size)
$bmp.Save($args[0])
