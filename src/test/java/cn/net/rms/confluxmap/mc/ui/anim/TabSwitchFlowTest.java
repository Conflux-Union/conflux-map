package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.mc.ui.anim.TabSwitchFlow.ClickDecision;
import cn.net.rms.confluxmap.mc.ui.anim.TabSwitchFlow.LandAction;
import cn.net.rms.confluxmap.mc.ui.anim.TabSwitchFlow.Landing;
import cn.net.rms.confluxmap.mc.ui.anim.TabSwitchFlow.Phase;
import org.junit.jupiter.api.Test;

final class TabSwitchFlowTest {
    private static final String A = "map";
    private static final String B = "radar";
    private static final String C = "waypoints";

    @Test
    void disabledTransitionsAlwaysRebuildInstantly() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        assertEquals(ClickDecision.REBUILD_NOW, flow.click(B, A, false));
        assertEquals(Phase.IDLE, flow.phase());

        // A transition armed before the config flipped off still collapses to a rebuild.
        assertEquals(ClickDecision.BEGIN_RETIRE, flow.click(B, A, true));
        assertEquals(ClickDecision.REBUILD_NOW, flow.click(C, A, false));
        assertEquals(Phase.IDLE, flow.phase());
    }

    @Test
    void clickingTheHighlightedTabDoesNothing() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        assertEquals(ClickDecision.NONE, flow.click(A, A, true));
        assertEquals(Phase.IDLE, flow.phase());

        // While retiring toward B the highlight already shows B, so clicking it again
        // must not restart the act.
        assertEquals(ClickDecision.BEGIN_RETIRE, flow.click(B, A, true));
        assertEquals(ClickDecision.NONE, flow.click(B, A, true));
        assertEquals(Phase.RETIRING, flow.phase());
        assertEquals(B, flow.highlighted(A));

        // Same once B's reveal is running: B is the selected tab there.
        assertEquals(new Landing<>(LandAction.REBUILD_AND_REVEAL, B), flow.land());
        assertEquals(ClickDecision.NONE, flow.click(B, B, true));
        assertEquals(Phase.REVEALING, flow.phase());
    }

    @Test
    void freshClickStartsTheRetireAct() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        assertEquals(ClickDecision.BEGIN_RETIRE, flow.click(B, A, true));
        assertEquals(Phase.RETIRING, flow.phase());
        assertEquals(B, flow.highlighted(A));
    }

    @Test
    void landedRetireRebuildsForTheQueuedTabAndReplaysTheReveal() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        flow.click(B, A, true);
        assertEquals(new Landing<>(LandAction.REBUILD_AND_REVEAL, B), flow.land());
        assertEquals(Phase.REVEALING, flow.phase());
        // The landing consumed the queued tab: the highlight tracks the selected one again.
        assertEquals(B, flow.highlighted(B));
    }

    @Test
    void settledRevealHandsTheRowsBack() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        flow.click(B, A, true);
        flow.land();
        assertEquals(new Landing<>(LandAction.SETTLE, null), flow.land());
        assertEquals(Phase.IDLE, flow.phase());
    }

    @Test
    void clickDuringRetireFastForwardsIntoTheNewTabsReveal() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        flow.click(B, A, true);
        assertEquals(ClickDecision.FAST_FORWARD, flow.click(C, A, true));
        assertEquals(C, flow.highlighted(A));
        // The cut-short retire lands straight into the queued tab's reveal, skipping the
        // reveal of the tab it was leaving toward.
        assertEquals(new Landing<>(LandAction.REBUILD_AND_REVEAL, C), flow.land());
        assertEquals(Phase.REVEALING, flow.phase());
        assertEquals(new Landing<>(LandAction.SETTLE, null), flow.land());
        assertEquals(Phase.IDLE, flow.phase());
    }

    @Test
    void clickDuringRevealSwitchesStraightToTheQueuedTabsReveal() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        flow.click(B, A, true);
        flow.land();
        assertEquals(ClickDecision.FAST_FORWARD, flow.click(C, B, true));
        assertEquals(C, flow.highlighted(B));
        assertEquals(new Landing<>(LandAction.REBUILD_AND_REVEAL, C), flow.land());
        assertEquals(Phase.REVEALING, flow.phase());
        assertEquals(new Landing<>(LandAction.SETTLE, null), flow.land());
        assertEquals(Phase.IDLE, flow.phase());
    }

    @Test
    void clickingThePreTransitionTabMidRetireRestartsTowardIt() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        flow.click(B, A, true);
        // The highlight shows B, so A is just another tab: the switch turns around to it.
        assertEquals(ClickDecision.FAST_FORWARD, flow.click(A, A, true));
        assertEquals(A, flow.highlighted(A));
        assertEquals(new Landing<>(LandAction.REBUILD_AND_REVEAL, A), flow.land());
        assertEquals(Phase.REVEALING, flow.phase());
    }

    @Test
    void resetAbandonsARunningTransition() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        flow.click(B, A, true);
        flow.reset();
        assertEquals(Phase.IDLE, flow.phase());
        assertEquals(A, flow.highlighted(A));
        assertEquals(new Landing<>(LandAction.NONE, null), flow.land());
    }

    @Test
    void landingWhileIdleReportsNothing() {
        final TabSwitchFlow<String> flow = new TabSwitchFlow<>();
        assertEquals(new Landing<>(LandAction.NONE, null), flow.land());
        assertEquals(Phase.IDLE, flow.phase());
    }
}
