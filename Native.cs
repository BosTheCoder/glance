using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;

namespace Glance;

/// The Win32/DWM calls WPF doesn't expose: acrylic backdrop, whole-window alpha, per-monitor work area.
static class Native
{
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr h, int attr, ref int val, int size);
    [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr h, int i);
    [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr h, int i, int v);
    [DllImport("user32.dll")] static extern bool SetLayeredWindowAttributes(IntPtr h, uint key, byte alpha, uint flags);
    [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr h, uint flags);
    [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr m, ref MonitorInfo mi);
    [StructLayout(LayoutKind.Sequential)] struct RectI { public int L, T, R, B; }
    [StructLayout(LayoutKind.Sequential)] struct MonitorInfo { public int Size; public RectI Monitor, Work; public uint Flags; }

    const int GWL_EXSTYLE = -20, WS_EX_LAYERED = 0x80000, WS_EX_TOOLWINDOW = 0x80, WM_STYLECHANGING = 0x7C;

    public static void Init(Window w, IntPtr hwnd)
    {
        HwndSource.FromHwnd(hwnd).CompositionTarget.BackgroundColor = Colors.Transparent;
        int acrylic = 3, round = 2;
        DwmSetWindowAttribute(hwnd, 38, ref acrylic, 4);   // DWMWA_SYSTEMBACKDROP_TYPE = transient (acrylic)
        DwmSetWindowAttribute(hwnd, 33, ref round, 4);     // DWMWA_WINDOW_CORNER_PREFERENCE = round
        // WPF vetoes WS_EX_LAYERED on non-transparent windows; keep it so the whole window can fade.
        HwndSource.FromHwnd(hwnd).AddHook((IntPtr h, int msg, IntPtr wp, IntPtr lp, ref bool handled) =>
        {
            if (msg == WM_STYLECHANGING && (int)wp == GWL_EXSTYLE)
            {
                Marshal.WriteInt32(lp, 4, Marshal.ReadInt32(lp, 4) | WS_EX_LAYERED);   // STYLESTRUCT.styleNew
                handled = true;
            }
            return IntPtr.Zero;
        });
        SetWindowLong(hwnd, GWL_EXSTYLE, GetWindowLong(hwnd, GWL_EXSTYLE) | WS_EX_LAYERED);
    }

    public static void DarkBackdrop(IntPtr hwnd, bool dark)
    {
        int v = dark ? 1 : 0;
        DwmSetWindowAttribute(hwnd, 20, ref v, 4);   // DWMWA_USE_IMMERSIVE_DARK_MODE tints the acrylic
    }

    /// Tool windows stay out of Alt+Tab and the taskbar.
    public static void ToolWindow(IntPtr hwnd, bool on)
    {
        var ex = GetWindowLong(hwnd, GWL_EXSTYLE);
        SetWindowLong(hwnd, GWL_EXSTYLE, on ? ex | WS_EX_TOOLWINDOW : ex & ~WS_EX_TOOLWINDOW);
    }

    public static void Alpha(IntPtr hwnd, double a) => SetLayeredWindowAttributes(hwnd, 0, (byte)(a * 255), 2);

    public static Rect WorkArea(Window w, IntPtr hwnd)
    {
        var mi = new MonitorInfo { Size = Marshal.SizeOf<MonitorInfo>() };
        if (hwnd == IntPtr.Zero || !GetMonitorInfo(MonitorFromWindow(hwnd, 2), ref mi)) return SystemParameters.WorkArea;
        var m = PresentationSource.FromVisual(w)!.CompositionTarget!.TransformFromDevice;
        return new Rect(m.Transform(new Point(mi.Work.L, mi.Work.T)), m.Transform(new Point(mi.Work.R, mi.Work.B)));
    }
}
