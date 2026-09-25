using Glance;
using Xunit;

public class DockingTests
{
    // A 300-wide widget on a 0..1920 screen.
    [Fact]
    public void Throw_docks_towards_the_throw_wherever_it_is()
    {
        Assert.Equal("Left", Docking.Side(800, 300, 0, 1920, -2500));
        Assert.Equal("Right", Docking.Side(800, 300, 0, 1920, 2500));
        Assert.Null(Docking.Side(800, 300, 0, 1920, 600));   // an ordinary drag
    }

    [Fact]
    public void Pushing_mostly_off_an_edge_docks_there_but_a_little_does_not()
    {
        Assert.Equal("Right", Docking.Side(1920 - 150, 300, 0, 1920, 0));   // half off the right
        Assert.Equal("Left", Docking.Side(-150, 300, 0, 1920, 0));
        Assert.Null(Docking.Side(1920 - 250, 300, 0, 1920, 0));             // 50 px over
    }

    [Fact]
    public void Velocity_uses_only_the_moments_before_release()
    {
        var fast = new List<(long, double)> { (0, 0), (40, 80), (80, 160) };
        Assert.Equal(2000, Docking.Velocity(fast, 80));
        // Same throw, then held still for half a second before letting go: not a throw.
        Assert.Equal(0, Docking.Velocity(fast, 580));
    }
}
