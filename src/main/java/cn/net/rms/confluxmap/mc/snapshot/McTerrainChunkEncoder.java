package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.EncodedSection;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/** Copies compressed block-state containers while the client chunk is owned by the main thread. */
final class McTerrainChunkEncoder {
    private static final int LOCAL_PALETTE_MAX_BITS = 8;

    private final MinecraftClient client;

    McTerrainChunkEncoder(final MinecraftClient client) {
        this.client = client;
    }

    EncodedChunk capture(
        final int chunkX, final int chunkZ, final long sessionToken
    ) {
        final ClientWorld world = client.world;
        if (world == null) {
            return null;
        }
        final WorldChunk chunk = (WorldChunk) world.getChunkManager()
            .getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk == null) {
            return null;
        }

        final ChunkSection[] sections = chunk.getSectionArray();
        //#if MC>=11800
        //$$ final int minSectionY = world.getBottomSectionCoord();
        //#else
        final int minSectionY = 0;
        //#endif
        final int maxSectionY = minSectionY + sections.length - 1;
        final List<EncodedSection> encoded = new ArrayList<>(sections.length);
        int directPaletteBits = 15;
        for (int index = 0; index < sections.length; index++) {
            final ChunkSection section = sections[index];
            //#if MC>=11800
            //$$ if (section.isEmpty()) {
            //#else
            if (ChunkSection.isEmpty(section)) {
            //#endif
                continue;
            }
            //#if MC>=11800
            //$$ final PalettedContainer<net.minecraft.block.BlockState> states = section.getBlockStateContainer();
            //#else
            final PalettedContainer<net.minecraft.block.BlockState> states = section.getContainer();
            //#endif
            final byte[] packet = encode(states);
            final int bits = packet[0] & 0xFF;
            if (bits > LOCAL_PALETTE_MAX_BITS) {
                directPaletteBits = bits;
            }
            encoded.add(new EncodedSection(minSectionY + index, packet));
        }
        return new EncodedChunk(
            sessionToken,
            world.getTime(),
            chunkX,
            chunkZ,
            minSectionY,
            maxSectionY,
            LOCAL_PALETTE_MAX_BITS,
            directPaletteBits,
            Block.getRawIdFromState(Blocks.AIR.getDefaultState()),
            encoded
        );
    }

    private static byte[] encode(final PalettedContainer<net.minecraft.block.BlockState> states) {
        final ByteBuf bytes = Unpooled.buffer(Math.max(64, states.getPacketSize()));
        try {
            final PacketByteBuf packet = new PacketByteBuf(bytes);
            //#if MC>=11800
            //$$ states.writePacket(packet);
            //#else
            states.toPacket(packet);
            //#endif
            final byte[] result = new byte[bytes.readableBytes()];
            bytes.getBytes(bytes.readerIndex(), result);
            return result;
        } finally {
            bytes.release();
        }
    }
}
