package dev.xardas.nekoroshop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import java.util.function.Supplier;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The "Nekoro" creative tab, shared by all Nekoro mods with items (Nekoro Coins, Collector Edition, Shop). Each of them
 * registers it when no other did yet and adds its own items, so it exists once whichever of them are installed.
 */
public final class NekoroTab {
    private NekoroTab() { }

    public static final ResourceKey<CreativeModeTab> KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath("nekoro", "nekoro"));
    public static final String TITLE = "itemGroup.nekoro";
    /**
     * The Amethyst Cat Coin as an item of this mod, only for the tab's icon when Nekoro Coins is not installed.
     * It is in no creative tab, so it cannot be found in game.
     */
    public static final String ICON_NAME = "nekoro_icon";
    public static Supplier<Item> ICON;

    public static boolean registered() {
        return BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(KEY.location());
    }

    /** The Amethyst Cat Coin of Nekoro Coins, or this mod's copy of it without that mod. */
    public static ItemStack icon() {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("nekorocoins", "coin111"))
                .map(ItemStack::new).orElseGet(() -> new ItemStack(ICON.get()));
    }
}
