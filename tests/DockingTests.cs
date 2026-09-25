using Glance;
using Xunit;

public class DockingTests
{
    // A 300x200 widget on a 1920x1080 screen.
    static string? Side(double left, double top, double vx = 0, double vy = 0) => Docking.Side(left, top, 300, 200, 0, 0, 1920, vx, vy);

    [Fact]
    public void Throw_docks_towards_the_throw_wherever_it_is()
    {
        Assert.Equal("Left", Side(800, 400, vx: -2500));
        Assert.Equal("Right", Side(800, 400, vx: 2500));
        Assert.Equal("Top", Side(800, 400, vy: -2500));
        Assert.Equal("Top", Side(800, 400, vx: 1600, vy: -2500));   // mostly upward wins over the sideways part
        Assert.Null(Side(800, 400, vy: 2500));                      // there's no bottom dock
        Assert.Null(Side(800, 400, vx: 600));                       // an ordinary drag
    }

    [Fact]
    public void Pushing_mostly_off_an_edge_docks_there_but_a_little_does_not()
    {
        Assert.Equal("Right", Side(1920 - 150, 400));   // half off the right
        Assert.Equal("Left", Side(-150, 400));
        Assert.Equal("Top", Side(800, -100));
        Assert.Null(Side(1920 - 250, 400));             // 50 px over
        Assert.Null(Side(800, -40));
    }

    [Fact]
    public void Velocity_uses_only_the_moments_before_release()
    {
        var fast = new List<(long, double, double)> { (0, 0, 0), (40, 80, -40), (80, 160, -80) };
        Assert.Equal((2000.0, -1000.0), Docking.Velocity(fast, 80));
        // Same throw, then held still for half a second before letting go: not a throw.
        Assert.Equal((0.0, 0.0), Docking.Velocity(fast, 580));
    }
}
