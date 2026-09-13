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
        super(AddonTemplate.CATEGORY, "sus-block-finder", "Detects suspicious natural block patterns optimized.");
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
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.world == null || this.mc.player == null) return;

        BlockPos playerPos = this.mc.player.getBlockPos();
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        // Tạo danh sách 169 Chunk (13x13)
        if (chunkList.isEmpty() || currentChunkIndex >= chunkList.size()) {
            chunkList.clear();
            for (int cx = playerChunkX - 6; cx <= playerChunkX + 6; cx++) {
                for (int cz = playerChunkZ - 6; cz <= playerChunkZ + 6; cz++) {
                    chunkList.add(new long[]{cx, cz});
                }
            }
            currentChunkIndex = 0;
            this.susBlocks.clear(); // Làm mới danh sách khi bắt đầu vòng quét mới
        }

        // TỐI ƯU 3: Mỗi tick chỉ quét 15 Chunk thay vì 169 Chunk cùng lúc
        int chunksToProcess = 15;
        while (chunksToProcess > 0 && currentChunkIndex < chunkList.size()) {
            long[] chunkCoords = chunkList.get(currentChunkIndex);
            int chunkX = (int) chunkCoords[0];
            int chunkZ = (int) chunkCoords[1];

            // TỐI ƯU 1: Kiểm tra xem Chunk đã được load chưa, bỏ qua nếu chưa load
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
                for (int y = 14; y <= 320; y++) {
                    BlockPos pos = new BlockPos(startX + x, y, startZ + z);
                    Block centerBlock = chunk.getBlockState(pos).getBlock();

                    // TỐI ƯU 2: Lọc nhanh khối không thuộc targetFive
                    if (this.targetFive.contains(centerBlock)) {
                        if (checkSusPattern(pos, centerBlock)) {
                            this.susBlocks.add(pos);
                        }
                    }
                }
            }
        }
    }

    private boolean checkSusPattern(BlockPos pos, Block centerBlock) {
        int sameTypeCount = 0;
        Direction[] horizontalDirections = new Direction[]{
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
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

            if (height >= 3 && !this.alertedPillars.contains(bottomPos)) {
                this.alertedPillars.add(bottomPos);
                ChatUtils.info(String.format(
                    "[SusBlockFinder] Vertical stack detected! Height: %d at X: %d, Y: %d, Z: %d",
                    height, bottomPos.getX(), bottomPos.getY(), bottomPos.getZ()
                ));
            }
        }
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (this.susBlocks.isEmpty()) return;

        for (BlockPos pos : this.susBlocks) {
            Box box = new Box(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 100.0, pos.getZ() + 1.0
            );

            event.renderer.box(
                box,
                this.sideColor.get(),
                this.lineColor.get(),
                ShapeMode.Both,
                0
            );
        }
    }
}
