package cn.net.rms.confluxmap.core.net;

/**
 * {@code 0x05 S2C POLICY_UPDATE}: mid-session policy change. Reuses {@link HelloPolicyS2C.Flags}
 * and {@link HelloPolicyS2C.Budgets}; {@code worldId} and the dimension list are connection-stable
 * and never change after HELLO_POLICY.
 */
public record PolicyUpdateS2C(HelloPolicyS2C.Flags flags, HelloPolicyS2C.Budgets budgets) implements Message {
    @Override
    public int typeId() {
        return Proto.MSG_POLICY_UPDATE_S2C;
    }
}
