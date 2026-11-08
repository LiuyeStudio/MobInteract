package studio.liuye.mobinteract.pickup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import studio.liuye.mobinteract.config.EntryType;
import studio.liuye.mobinteract.config.PlacementEntry;
import studio.liuye.mobinteract.safety.BlockSafety;

/** 拾取物品的纯逻辑：能捡什么、捡到的方块变成哪个背包条目。 */
class PickedItemsTest {

    @Test
    void pickaxesAreRecognizedByMaterialName() {
        assertTrue(PickedItems.isPickaxe(Material.WOODEN_PICKAXE));
        assertTrue(PickedItems.isPickaxe(Material.NETHERITE_PICKAXE));
        assertFalse(PickedItems.isPickaxe(Material.IRON_SWORD));
        assertFalse(PickedItems.isPickaxe(Material.COBBLESTONE));
        assertFalse(PickedItems.isPickaxe(null));
    }

    @Test
    void hardBlacklistedMaterialsAreNeverStored() {
        // canStore 本身依赖服务端注册表（Material#isBlock），这里只校验黑名单这一半。
        assertTrue(BlockSafety.isForbidden(Material.BEDROCK));
        assertTrue(BlockSafety.isForbidden(Material.BARRIER));
        assertTrue(BlockSafety.isForbidden(Material.COMMAND_BLOCK));
        assertFalse(BlockSafety.isForbidden(Material.COBBLESTONE));
        assertFalse(BlockSafety.isForbidden(Material.DIRT));
    }

    @Test
    void configuredEntryIsReusedForTheSameMaterial() {
        PlacementEntry cobble = new PlacementEntry("cobblestone", EntryType.BLOCK, 30.0D,
                Material.COBBLESTONE, "", 0, null, true);

        PlacementEntry picked = PickedItems.entryFor(List.of(cobble), Material.COBBLESTONE);

        assertSame(cobble, picked, "配置里已有同材质条目时直接复用，保留原权重");
    }

    @Test
    void unknownMaterialGetsAStablePickedEntry() {
        PlacementEntry first = PickedItems.entryFor(List.of(), Material.DIORITE);
        PlacementEntry second = PickedItems.entryFor(null, Material.DIORITE);

        assertEquals(first, second, "同一材质的条目必须一致，否则背包会重复记账");
        assertEquals(EntryType.BLOCK, first.type());
        assertEquals(Material.DIORITE, first.material());
        assertEquals(PickedItems.PICKED_WEIGHT, first.weight(), 1.0E-9D);
        assertTrue(first.id().startsWith(PickedItems.PICKED_ID_PREFIX), first.id());
        assertNotEquals("", first.id());
    }

    @Test
    void fluidOrTntEntriesAreNotReusedForBlocks() {
        PlacementEntry lava = new PlacementEntry("lava_bucket", EntryType.FLUID, 2.0D,
                Material.LAVA_BUCKET, "", 0, studio.liuye.mobinteract.config.FluidKind.LAVA, true);

        PlacementEntry picked = PickedItems.entryFor(List.of(lava), Material.COBBLESTONE);

        assertNotEquals(lava, picked);
        assertEquals(Material.COBBLESTONE, picked.material());
    }
}
