namespace Glance;

/// Docking to a screen edge: where a released drag should go. UI-free so the tests can run it.
public static class Docking
{
    public const double Fling = 1500;   // DIPs per second; an ordinary drag stays well under this

    /// "Left" or "Right" if the drag was thrown hard sideways or pushed mostly off that edge, else null (stay put).
    public static string? Side(double left, double width, double edgeLeft, double edgeRight, double vx)
    {
        if (vx <= -Fling) return "Left";
        if (vx >= Fling) return "Right";
        if (left < edgeLeft - width * 0.4) return "Left";
        if (left + width > edgeRight + width * 0.4) return "Right";
        return null;
    }

    /// Horizontal speed over the last ~100 ms before release. Zero if the pointer had stopped before letting go.
    public static double Velocity(IReadOnlyList<(long Ms, double X)> samples, long releaseMs, int windowMs = 100)
    {
        var recent = samples.Where(p => releaseMs - p.Ms <= windowMs).ToList();
        if (recent.Count < 2 || recent[^1].Ms == recent[0].Ms) return 0;
        return (recent[^1].X - recent[0].X) * 1000.0 / (recent[^1].Ms - recent[0].Ms);
    }
}
