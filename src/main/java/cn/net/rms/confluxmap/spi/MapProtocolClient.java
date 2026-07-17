package cn.net.rms.confluxmap.spi;

/**
 * Extension seam reserved for the planned M2+ dual-sided protocol: a lightweight
 * server-side companion (a Fabric server mod, or a plugin on other loaders) that a
 * client running Conflux Map can optionally detect and talk to over a custom network
 * channel, to unlock behavior a client cannot infer from vanilla packets alone -
 * for example sharing waypoints across a party/team, enforcing a server-side map
 * policy (e.g. disabling cave view on a survival server), or exchanging the world
 * seed so {@link PredictionProvider} can start predicting immediately instead of
 * waiting for exploration.
 *
 * <p>M1 is entirely single-sided: the client works from client-observed data alone
 * (chunk packets, block updates) against any server, vanilla or modded, with zero
 * server-side requirement. Nothing in the mod implements or references this type -
 * it exists purely to reserve the package and name (see the {@code checkCoreIsolation}
 * Gradle task, which the {@code spi} package is subject to the same as
 * {@code core}/{@code bridge}: no Minecraft/Fabric imports) so M2's handshake and
 * message-codec work has a clear, pre-agreed landing spot instead of being bolted on
 * wherever is convenient later.
 */
public interface MapProtocolClient {
}
