package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.SummaryCodec;

/** Fixed 256x256 server-side sample plane consumed by correction patch building. */
public interface SummaryView {
    int lod();

    long originBlockX();

    long originBlockZ();

    long revision();

    byte[] presence();

    Pixel pixel(int pixelX, int pixelZ);

    record Pixel(boolean generated, long revision, SummaryCodec.Column column) {
    }
}
