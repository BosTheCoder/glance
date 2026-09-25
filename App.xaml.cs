using System.IO;
using System.Windows;

namespace Glance;

public partial class App : Application
{
    static Mutex? single;

    protected override void OnStartup(StartupEventArgs e)
    {
        var demo = e.Args.Contains("--demo");
        single = new Mutex(true, demo ? "Glance.Demo" : "Glance.SingleInstance", out var fresh);
        if (!fresh) { Shutdown(); return; }
        DispatcherUnhandledException += (_, ex) =>
        {
            File.AppendAllText(Path.Combine(AppContext.BaseDirectory, "glance.log"), $"{DateTime.Now:o} {ex.Exception}\n");
            ex.Handled = true;
        };
        new MainWindow(demo).Show();
    }
}
