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

    public ModuleExample() {
        super(AddonTemplate.CATEGORY, "sus-block-finder", "Detects suspicious natural block patterns and vertical stacks.");
    }

    @Override
    public void onActivate() {
        this.susBlocks.clear();
        this.alertedPillars.clear();

        // 5 loại khối theo dõi: Stone, Granite, Diorite, Andesite, Gravel
        this.targetFive.clear();
        this.targetFive.add(Blocks.STONE);
        this.targetFive.add(Blocks.GRANITE);
        this.targetFive.add(Blocks.DIORITE);
        this.targetFive.add(Blocks.ANDESITE);
        this.targetFive.add(Blocks.GRAVEL);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (this.mc.world == null || this.mc.player == null) return;

        this.susBlocks.clear();
        BlockPos playerPos = this.mc.player.getBlockPos();

        int rad = this.customRadius.get();

        for (int x = -rad; x <= rad; x++) {
            for (int y = -rad; y <= rad; y++) {
                for (int z = -rad; z <= rad; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    Block centerBlock = this.mc.world.getBlockState(pos).getBlock();

                    if (this.targetFive.contains(centerBlock)) {
                        if (checkSusPattern(pos, centerBlock)) {
                            this.susBlocks.add(pos);
                        }
                    }
                }
            }
        }

        checkVerticalPillars();
    }

    private boolean checkSusPattern(BlockPos pos, Block centerBlock) {
        Map<Block, Integer> neighborCounts = new HashMap<>();
        Direction[] horizontalDirections = new Direction[]{
            Direction.NORTH,
            Direction.SOUTH,
            Direction.WEST,
            Direction.EAST
        };

        for (Direction dir : horizontalDirections) {
            BlockPos neighborPos = pos.offset(dir);
            Block neighborBlock = this.mc.world.getBlockState(neighborPos).getBlock();

            if (this.targetFive.contains(neighborBlock) && neighborBlock != centerBlock) {
                neighborCounts.put(neighborBlock, neighborCounts.getOrDefault(neighborBlock, 0) + 1);
            }
        }

        for (int count : neighborCounts.values()) {
            if (count >= 3) {
                return true;
            }
        }

        return false;
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

            if (height >= 3) {
                if (!this.alertedPillars.contains(bottomPos)) {
                    this.alertedPillars.add(bottomPos);
                    if (this.notifyChat.get()) {
                        ChatUtils.info(String.format(
                            "[SusBlockFinder] Suspicious vertical stack detected! Height: %d at X: %d, Y: %d, Z: %d",
                            height, bottomPos.getX(), bottomPos.getY(), bottomPos.getZ()
                        ));
                    }
                }
            }
        }
    }

    @EventHandler
    private void onRender3d(Render3DEvent event) {
        if (this.susBlocks.isEmpty()) return;

        for (BlockPos pos : this.susBlocks) {
            Box box = new AABB(pos);
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
