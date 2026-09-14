package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class ModuleExample extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Boolean> notifyChat = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notification")
        .description("Send chat message when a vertical stack of height >= 3 is detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Side color of highlighted sus blocks.")
        .defaultValue(new SettingColor(255, 165, 0, 75))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line color of highlighted sus blocks.")
        .defaultValue(new SettingColor(255, 140, 0, 255))
        .build()
    );

    private final Set<BlockPos> susBlocks = new HashSet<>();
    private final Set<BlockPos> alertedPillars = new HashSet<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final List<Block> targetFive = new ArrayList<>();
    private final Queue<ChunkPos> chunksToScan = new LinkedList<>();

    public ModuleExample() {
        super(AddonTemplate.CATEGORY, "sus-block-finder", "Detects suspicious natural block patterns optimized for packet updates.");
    }

    @Override
    public void onActivate() {
        this.susBlocks.clear();
        this.alertedPillars.clear();
        this.scannedChunks.clear();
        this.chunksToScan.clear();

        this.targetFive.clear();
        this.targetFive.add(Blocks.STONE);
        this.targetFive.add(Blocks.GRANITE);
        this.targetFive.add(Blocks.DIORITE);
        this.targetFive.add(Blocks.ANDESITE);
        this.targetFive.add(Blocks.GRAVEL);
    }

    // 1. LẮNG NGHE KHI SERVER GỬI PACKET CHUNK MỚI HOẶC NẠP THÊM DỮ LIỆU CHUNK
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        WorldChunk chunk = event.chunk;
        if (chunk != null) {
            ChunkPos cPos = chunk.getPos();
            // Đưa Chunk này vào danh sách cần quét/quét lại
            if (!chunksToScan.contains(cPos)) {
                chunksToScan.add(cPos);
            }
        }
    }

    // 2. LẮNG NGHE KHI CÓ KHỐI TRONG CHUNK THAY ĐỔI
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        ChunkPos cPos = new ChunkPos(pos);
        // Đánh dấu Chunk chứa khối này cần quét lại
        scannedChunks.remove(cPos);
        if (!chunksToScan.contains(cPos)) {
            chunksToScan.add(cPos);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.world == null || this.mc.player == null) return;

        BlockPos playerPos = this.mc.player.getBlockPos();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        // Bổ sung các Chunk chưa từng nạp trong bán kính 13x13 (6 chunk quanh player)
        for (int cx = playerChunkX - 6; cx <= playerChunkX + 6; cx++) {
            for (int cz = playerChunkZ - 6; cz <= playerChunkZ + 6; cz++) {
                ChunkPos cPos = new ChunkPos(cx, cz);
                if (!scannedChunks.contains(cPos) && !chunksToScan.contains(cPos)) {
                    chunksToScan.add(cPos);
                }
            }
        }

        // Xử lý đúng 1 Chunk / 1 Tick khi có sự kiện cập nhật
        if (!chunksToScan.isEmpty()) {
            ChunkPos targetChunkPos = chunksToScan.poll();
            if (this.mc.world.getChunkManager().isChunkLoaded(targetChunkPos.x, targetChunkPos.z)) {
                WorldChunk chunk = this.mc.world.getChunk(targetChunkPos.x, targetChunkPos.z);
                scanChunk(chunk);
                scannedChunks.add(targetChunkPos);
            }
        }

        checkVerticalPillars();
    }

    private void scanChunk(WorldChunk chunk) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 14; y <= 320; y++) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    Block centerBlock = chunk.getBlockState(pos).getBlock();

                    if (this.targetFive.contains(centerBlock)) {
                        if (checkSusPattern(pos, centerBlock)) {
                            this.susBlocks.add(pos);
                        } else {
                            this.susBlocks.remove(pos);
                        }
                    } else {
                        this.susBlocks.remove(pos);
                    }
                }
            }
        }
    }

    private boolean checkSusPattern(BlockPos pos, Block centerBlock) {
        int horizontalSameCount = 0;
        Direction[] horizontalDirections = new Direction[]{
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
        };

        for (Direction dir : horizontalDirections) {
            Block neighborBlock = this.mc.world.getBlockState(pos.offset(dir)).getBlock();
            if (neighborBlock == centerBlock) {
                horizontalSameCount++;
            }
        }

        if (horizontalSameCount < 4) return false;

        Block topBlock = this.mc.world.getBlockState(pos.up()).getBlock();
        Block bottomBlock = this.mc.world.getBlockState(pos.down()).getBlock();

        return (topBlock == centerBlock || bottomBlock == centerBlock);
    }

    private void checkVerticalPillars() {
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos pos : new ArrayList<>(this.susBlocks)) {
            if (visited.contains(pos)) continue;

            BlockPos bottomPos = pos;
            while (this.susBlocks.contains(bottomPos.down())) {
                bottomPos = bottomPos.down();
            }

            int height = 0;
            BlockPos current = bottomPos;
            while (this.susBlocks.contains(current)) {
                visited.add(current);
                height++;
                current = current.up();
            }

            if (height >= 3 && !this.alertedPillars.contains(bottomPos)) {
                this.alertedPillars.add(bottomPos);
                if (this.notifyChat.get()) {
                    ChatUtils.info(String.format(
                        "[SusBlockFinder] Vertical stack detected! Height: %d at X: %d, Y: %d, Z: %d",
                        height, bottomPos.getX(), bottomPos.getY(), bottomPos.getZ()
                    ));
                }
            }
        }
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (this.susBlocks.isEmpty()) return;

        Set<BlockPos> visited = new HashSet<>();
        int renderCount = 0;
        int maxRenderLimit = 30;

        for (BlockPos pos : new ArrayList<>(this.susBlocks)) {
            if (visited.contains(pos)) continue;

            BlockPos bottomPos = pos;
            while (this.susBlocks.contains(bottomPos.down())) {
                bottomPos = bottomPos.down();
            }

            int height = 0;
            BlockPos current = bottomPos;
            while (this.susBlocks.contains(current)) {
                visited.add(current);
                height++;
                current = current.up();
            }

            if (height >= 3) {
                if (renderCount >= maxRenderLimit) break;

                Box box = new Box(
                    bottomPos.getX(), bottomPos.getY(), bottomPos.getZ(),
                    bottomPos.getX() + 1.0, bottomPos.getY() + 100.0, bottomPos.getZ() + 1.0
                );

                event.renderer.box(
                    box,
                    this.sideColor.get(),
                    this.lineColor.get(),
                    ShapeMode.Lines,
                    0
                );

                renderCount++;
            }
        }
    }
}
