package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.QuadrantLayout;
import java.util.List;

/**
 * {@code 0x15 S2C QUADRA_LAYOUT}: the quadra-gen quadrant layout of every managed dimension,
 * sent immediately <em>before</em> {@code HELLO_POLICY} (like {@code FLAT_BASELINE}) so it is
 * stored by the time the policy activates the session. A client that predates this message logs
 * one "undecodable payload" warning and keeps its unmasked prediction, so it is safe to send
 * unconditionally to capability-advertising peers.
 *
 * @param entries one entry per managed dimension; empty is never sent
 */
public record QuadraLayoutS2C(List<Entry> entries) implements Message {

    /**
     * @param dimIndex index into {@code HELLO_POLICY}'s dim list (same space as
     *                 {@link MapViewReqC2S#dimIndex()})
     * @param layout   the dimension's four-quadrant layout, in {@link QuadrantLayout} wire order
     */
    public record Entry(int dimIndex, QuadrantLayout layout) {
    }

    @Override
    public int typeId() {
        return Proto.MSG_QUADRA_LAYOUT_S2C;
    }
}
