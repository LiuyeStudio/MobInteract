package studio.liuye.mobinteract.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BreakTimeCalculatorTest {

    @Test
    void unbreakableBlocksAreRejected() {
        assertTrue(BreakTimeCalculator.baseSeconds(-1.0F) < 0.0D);
        assertTrue(BreakTimeCalculator.monsterSeconds(-1.0F, 2.0D, 0.2D, 45.0D) < 0.0D);
    }

    @Test
    void baseTimeUsesHardnessTimesHandSpeed() {
        // 石头硬度 1.5：玩家空手约 2.25 秒。
        assertEquals(2.25D, BreakTimeCalculator.baseSeconds(1.5F), 1.0E-6D);
        // 黑曜石硬度 50：约 75 秒（超过上限后视为不可挖）。
        assertEquals(75.0D, BreakTimeCalculator.baseSeconds(50.0F), 1.0E-6D);
    }

    @Test
    void monsterTimeAppliesMultiplierAndCap() {
        assertEquals(4.5D, BreakTimeCalculator.monsterSeconds(1.5F, 2.0D, 0.2D, 45.0D), 1.0E-6D);
        assertTrue(BreakTimeCalculator.monsterSeconds(50.0F, 2.0D, 0.2D, 45.0D) < 0.0D,
                "超过 hard-cap-seconds 应视为不可挖");
    }

    @Test
    void minimumTimeIsAppliedToZeroHardnessBlocks() {
        assertEquals(0.2D, BreakTimeCalculator.monsterSeconds(0.0F, 2.0D, 0.2D, 45.0D), 1.0E-6D);
    }

    @Test
    void hugeMultiplierEffectivelyDisablesBlock() {
        assertTrue(BreakTimeCalculator.monsterSeconds(1.5F, 1000.0D, 0.2D, 45.0D) < 0.0D);
    }

    @Test
    void hardBlocksNeedAWideEnoughCap() {
        // 黑曜石：硬度 50 → 基础 75 s；默认倍率 2 → 木镐 150 s / 钻石镐 37.5 s。
        assertEquals(75.0D, BreakTimeCalculator.baseSeconds(50.0F), 1.0E-6D);
        // 旧的默认上限 45 s：木镐会被判“挖不动”（这正是“黑曜石怎么不挖”的原因）。
        assertTrue(BreakTimeCalculator.monsterSeconds(50.0F, 2.0D, 0.2D, 45.0D, 1.0D) < 0.0D);
        // 默认上限放宽到 180 s 之后：木镐 150 s、钻石镐 37.5 s 都能算出来。
        assertEquals(150.0D, BreakTimeCalculator.monsterSeconds(50.0F, 2.0D, 0.2D, 180.0D, 1.0D), 1.0E-6D);
        assertEquals(37.5D, BreakTimeCalculator.monsterSeconds(50.0F, 2.0D, 0.2D, 180.0D, 4.0D), 1.0E-6D);
    }

    @Test
    void totalTicksRoundsUp() {
        assertEquals(45, BreakTimeCalculator.totalTicks(2.25D));
        assertEquals(1, BreakTimeCalculator.totalTicks(0.0D));
        assertEquals(1, BreakTimeCalculator.totalTicks(0.001D));
        assertEquals(20, BreakTimeCalculator.totalTicks(1.0D));
    }

    @Test
    void betterPickaxeReducesTime() {
        // 木镐系数 1.0：石头 2.25 × 2 = 4.5 秒
        assertEquals(4.5D, BreakTimeCalculator.monsterSeconds(1.5F, 2.0D, 0.2D, 45.0D, 1.0D), 1.0E-6D);
        // 石镐系数 2.0：2.25 × 2 ÷ 2 = 2.25 秒
        assertEquals(2.25D, BreakTimeCalculator.monsterSeconds(1.5F, 2.0D, 0.2D, 45.0D, 2.0D), 1.0E-6D);
        // 铁镐系数 3.0
        assertEquals(1.5D, BreakTimeCalculator.monsterSeconds(1.5F, 2.0D, 0.2D, 45.0D, 3.0D), 1.0E-6D);
        // 空手系数 0.5：比木镐慢一倍
        assertEquals(9.0D, BreakTimeCalculator.monsterSeconds(1.5F, 2.0D, 0.2D, 45.0D, 0.5D), 1.0E-6D);
        // 非法速度按 1.0 处理
        assertEquals(4.5D, BreakTimeCalculator.monsterSeconds(1.5F, 2.0D, 0.2D, 45.0D, 0.0D), 1.0E-6D);
    }

    @Test
    void minimumTimeStillAppliesWithToolSpeed() {
        assertEquals(0.2D, BreakTimeCalculator.monsterSeconds(0.0F, 2.0D, 0.2D, 45.0D, 3.0D), 1.0E-6D);
    }
}
