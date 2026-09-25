using System.IO;
using System.Windows;

namespace Glance;

public partial class App : Application
{
    static Mutex? single;

    protected override void OnStartup(StartupEventArgs e)
    {
        var demo = e.Args.Contains("--demo");
        var name = demo ? "Glance.Demo" : "Glance.SingleInstance";
        single = new Mutex(true, name, out var fresh);
        // Launching Glance again (Start menu, shortcut) brings a hidden widget back instead of doing nothing.
        var reveal = new EventWaitHandle(false, EventResetMode.AutoReset, name + ".Reveal");
        if (!fresh) { reveal.Set(); Shutdown(); return; }
        DispatcherUnhandledException += (_, ex) =>
        {
            File.AppendAllText(Path.Combine(AppContext.BaseDirectory, "glance.log"), $"{DateTime.Now:o} {ex.Exception}\n");
            ex.Handled = true;
        };
        var w = new MainWindow(demo);
        w.Show();
        new Thread(() => { while (reveal.WaitOne()) w.Dispatcher.Invoke(w.Reveal); }) { IsBackground = true }.Start();
    }
}
