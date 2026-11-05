package dev.xardas.nekoroshop;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** Shared constants and the registered objects; each loader registers them and fills in the suppliers. */
public final class NekoroShop {
    public static final String MOD_ID = "nekoroshop";
    public static final Logger LOGGER = LoggerFactory.getLogger("Nekoro Shop");

    public static final String BLOCK_NAME = "shop_table";
    public static Supplier<Block> BLOCK;
    public static Supplier<Item> ITEM;
    public static final String CATALOG_NAME = "shop_catalog";
    public static Supplier<Item> CATALOG;
    public static final String MENU_NAME = "shop";
    public static Supplier<MenuType<ShopMenu>> MENU;

    private NekoroShop() { }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static Block createBlock() {
        return new ShopTableBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD));
    }

    public static Item createItem(Block block) {
        return new BlockItem(block, new Item.Properties());
    }

    public static Item createCatalog() {
        return new ShopCatalogItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    }

    public static MenuType<ShopMenu> createMenu() {
        return new MenuType<>(ShopMenu::new, FeatureFlags.VANILLA_SET);
    }
}
