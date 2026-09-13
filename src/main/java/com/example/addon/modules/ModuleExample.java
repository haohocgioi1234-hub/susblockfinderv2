package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

public class ModuleExample extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgRender = this.settings.createGroup("Render");

    private final Setting<Boolean> useRenderDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("use-render-distance")
        .description("Use game render distance as scan radius automatically.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> customRadius = sgGeneral.add(new IntSetting.Builder()
        .name("custom-radius")
        .description("Custom scan radius in blocks.")
        .defaultValue(16)
        .min(1)
        .max(64)
        .visible(() -> !useRenderDistance.get())
        .build()
    );

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
    private final List<Block> targetFive = new ArrayList<>();

    private int currentChunkIndex = 0;
    private final List<long[]> chunkList = new ArrayList<>();

    public ModuleExample() {
        super(AddonTemplate.CATEGORY, "sus-block-finder", "Detects suspicious natural block patterns and vertical stacks.");
    }

    @Override
    public void onActivate() {
        this.susBlocks.clear();
        this.alertedPillars.clear();

        this.targetFive.clear();
        this.targetFive.add(Blocks.STONE);
        this.targetFive.add(Blocks.GRANITE);
        this.targetFive.add(Blocks.DIORITE);
        this.targetFive.add(Blocks.ANDESITE);
        this.targetFive.add(Blocks.GRAVEL);

        this.currentChunkIndex = 0;
        this.chunkList.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.world == null || this.mc.player == null) return;

        BlockPos playerPos = this.mc.player.getBlockPos();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        // Tạo danh sách 169 Chunk (13x13 Chunk quanh người chơi)
        if (chunkList.isEmpty() || currentChunkIndex >= chunkList.size()) {
            chunkList.clear();
            for (int cx = playerChunkX - 6; cx <= playerChunkX + 6; cx++) {
                for (int cz = playerChunkZ - 6; cz <= playerChunkZ + 6; cz++) {
                    chunkList.add(new long[]{cx, cz});
                }
            }
            currentChunkIndex = 0;
            this.susBlocks.clear();
        }

        // Mỗi tick chỉ xử lý 15 Chunk để duy trì FPS mượt mà
        int chunksToProcess = 15;
        while (chunksToProcess > 0 && currentChunkIndex < chunkList.size()) {
            long[] chunkCoords = chunkList.get(currentChunkIndex);
            int chunkX = (int) chunkCoords[0];
            int chunkZ = (int) chunkCoords[1];

            if (this.mc.world.getChunkManager().isChunkLoaded(chunkX, chunkZ)) {
                WorldChunk chunk = this.mc.world.getChunk(chunkX, chunkZ);
                scanChunk(chunk);
            }

            currentChunkIndex++;
            chunksToProcess--;
        }

        checkVerticalPillars();
    }

    private void scanChunk(WorldChunk chunk) {
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Chỉ quét từ Y = 14 trở lên
                for (int y = 14; y <= 320; y++) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    Block centerBlock = chunk.getBlockState(pos).getBlock();

                    if (this.targetFive.contains(centerBlock)) {
                        if (checkSusPattern(pos, centerBlock)) {
                            this.susBlocks.add(pos);
                        }
                    }
                }
            }
        }
    }

    // Kiểm tra 4 hướng ngang (XZ): Đông, Tây, Nam, Bắc
    private boolean checkSusPattern(BlockPos pos, Block centerBlock) {
        int sameTypeCount = 0;
        Direction[] horizontalDirections = new Direction[]{
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
        };

        for (Direction dir : horizontalDirections) {
            BlockPos neighborPos = pos.offset(dir);
            Block neighborBlock = this.mc.world.getBlockState(neighborPos).getBlock();

            if (neighborBlock == centerBlock) {
                sameTypeCount++;
            }
        }

        return sameTypeCount >= 4;
    }

    private void checkVerticalPillars() {
        Set<BlockPos> visited = new HashSet<>();

        for (BlockPos pos : this.susBlocks) {
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

            // Gửi chat notification khi phát hiện cột có height >= 3
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
        int maxRenderLimit = 50; // Giới hạn số cột vẽ để tránh lag

        for (BlockPos pos : this.susBlocks) {
            if (visited.contains(pos)) continue;

            // Tìm khối chân dưới cùng của cụm
            BlockPos bottomPos = pos;
            while (this.susBlocks.contains(bottomPos.down())) {
                bottomPos = bottomPos.down();
            }

            // Tính chiều cao liên tục của cụm
            int height = 0;
            BlockPos current = bottomPos;
            while (this.susBlocks.contains(current)) {
                visited.add(current);
                height++;
                current = current.up();
            }

            // CHỈ VẼ CỘT HIGHLIGHT KHI CHIỀU CAO CỘT >= 3
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
