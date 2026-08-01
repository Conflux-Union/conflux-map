package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import java.util.List;

/**
 * {@code 0x07 S2C FLAT_BASELINE}: the uniform surface of every superflat dimension the server
 * hosts, sent immediately <em>before</em> {@code HELLO_POLICY} so it is already stored when the
 * policy activates the session. A client that predates this message logs one "undecodable
 * payload" warning and simply keeps its prediction disabled for flat dims (the policy still
 * advertises them non-predictable), so the message is safe to send unconditionally.
 *
 * @param entries one entry per superflat dimension; empty is never sent
 */
public record FlatBaselineS2C(List<Entry> entries) implements Message {

    /**
     * @param dimIndex index into {@code HELLO_POLICY}'s dim list (same space as
     *                 {@link MapViewReqC2S#dimIndex()})
     * @param baseline the dimension's uniform surface
     */
    public record Entry(int dimIndex, FlatBaseline baseline) {
    }

    @Override
    public int typeId() {
        return Proto.MSG_FLAT_BASELINE_S2C;
    }
}
