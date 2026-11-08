package studio.liuye.mobinteract.mining;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import studio.liuye.mobinteract.config.MiningSettings;
import studio.liuye.mobinteract.util.BlockPos;

/**
 * 挖掘过程的视觉与听觉反馈。
 *
 * <p>全部在怪物所属区域的线程上调用；只做“读方块 + 发数据包”，
 * 不修改任何方块状态。</p>
 */
public final class MiningFeedback {

    private final Random random = new Random();

    /**
     * 向附近玩家发送方块破坏裂纹进度。
     *
     * @param progress 0.0 - 1.0
     */
    public void progress(MiningSettings settings, Mob mob, BlockPos pos, float progress) {
        if (!settings.progressViewers() || pos == null) {
            return;
        }
        World world = pos.world();
        if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            return;
        }
        Location at = pos.location();
        int entityId = mob.getEntityId();
        for (Player player : world.getNearbyPlayers(pos.center(), settings.progressRadius())) {
            player.sendBlockDamage(at, progress, entityId);
        }
    }

    /** 清除该怪物在该方块上留下的裂纹进度，避免残留。 */
    public void clear(Mob mob, BlockPos pos) {
        if (pos == null) {
            return;
        }
        World world = pos.world();
        if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            return;
        }
        Location at = pos.location();
        int entityId = mob.getEntityId();
        for (Player player : world.getPlayers()) {
            player.sendBlockDamage(at, 0.0F, entityId);
        }
    }

    /** 挖掘中的周期反馈：碎屑粒子 + 命中音效。 */
    public void hit(MiningSettings settings, BlockPos pos) {
        if (pos == null) {
            return;
        }
        World world = pos.world();
        if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            return;
        }
        Block block = pos.block();
        Location center = pos.center();
        if (settings.particles()) {
            spawnParticles(world, settings, center, block);
        }
        if (settings.sounds() && settings.hitSound() != null) {
            world.playSound(center, settings.hitSound(), SoundCategory.BLOCKS, 0.7F,
                    0.85F + random.nextFloat() * 0.3F);
        }
    }

    /** 方块被挖断时的反馈。 */
    public void broke(MiningSettings settings, BlockPos pos) {
        if (pos == null) {
            return;
        }
        World world = pos.world();
        if (!world.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            return;
        }
        Location center = pos.center();
        if (settings.sounds() && settings.breakSound() != null) {
            world.playSound(center, settings.breakSound(), SoundCategory.BLOCKS, 1.0F, 0.9F);
        }
        if (settings.particles()) {
            BlockData data = pos.block().getBlockData();
            world.spawnParticle(Particle.BLOCK, center, Math.max(8, settings.particleCount() * 3),
                    0.35D, 0.35D, 0.35D, data);
        }
    }

    private void spawnParticles(World world, MiningSettings settings, Location center, Block block) {
        Particle particle = settings.particle();
        if (particle == null) {
            return;
        }
        Class<?> dataType = particle.getDataType();
        if (dataType == Void.class) {
            world.spawnParticle(particle, center, settings.particleCount(), 0.3D, 0.3D, 0.3D);
            return;
        }
        if (BlockData.class.isAssignableFrom(dataType)) {
            world.spawnParticle(particle, center, settings.particleCount(), 0.3D, 0.3D, 0.3D,
                    block.getBlockData());
            return;
        }
        // 需要其它数据类型（如 DustOptions）的粒子不适合表达“挖方块”，退回无数据粒子。
        world.spawnParticle(Particle.CRIT, center, settings.particleCount(), 0.3D, 0.3D, 0.3D);
    }
}
