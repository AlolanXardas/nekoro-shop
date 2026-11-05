package dev.xardas.nekoroshop.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import dev.xardas.nekoroshop.NekoroShop;
import dev.xardas.nekoroshop.NekoroTab;
import dev.xardas.nekoroshop.Payloads;
import dev.xardas.nekoroshop.ShopCommands;
import dev.xardas.nekoroshop.ShopConfig;
import dev.xardas.nekoroshop.ShopMenu;
import dev.xardas.nekoroshop.ShopService;

/** Fabric entry point: registers the block, item and window and connects Fabric API events to the shared code. */
public final class NekoroShopFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        ShopConfig.loadSettings();
        ResourceLocation id = NekoroShop.id(NekoroShop.BLOCK_NAME);
        Block block = Registry.register(BuiltInRegistries.BLOCK, id, NekoroShop.createBlock());
        Item item = Registry.register(BuiltInRegistries.ITEM, id, NekoroShop.createItem(block));
        Item catalog = Registry.register(BuiltInRegistries.ITEM, NekoroShop.id(NekoroShop.CATALOG_NAME), NekoroShop.createCatalog());
        MenuType<ShopMenu> menu = Registry.register(BuiltInRegistries.MENU, NekoroShop.id(NekoroShop.MENU_NAME), NekoroShop.createMenu());
        NekoroShop.BLOCK = () -> block;
        NekoroShop.ITEM = () -> item;
        NekoroShop.CATALOG = () -> catalog;
        NekoroShop.MENU = () -> menu;
        Item icon = Registry.register(BuiltInRegistries.ITEM, NekoroShop.id(NekoroTab.ICON_NAME), new Item(new Item.Properties()));
        NekoroTab.ICON = () -> icon;
        if (!NekoroTab.registered()) {
            Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, NekoroTab.KEY, FabricItemGroup.builder()
                    .title(Component.translatable(NekoroTab.TITLE)).icon(NekoroTab::icon).build());
        }
        ItemGroupEvents.modifyEntriesEvent(NekoroTab.KEY).register(entries -> {
            entries.accept(item);
            entries.accept(catalog);
        });

        PayloadTypeRegistry.playS2C().register(Payloads.Rules.TYPE, Payloads.Rules.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Balance.TYPE, Payloads.Balance.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Market.TYPE, Payloads.Market.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(Payloads.Notice.TYPE, Payloads.Notice.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.Trade.TYPE, Payloads.Trade.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Payloads.Trade.TYPE,
                (payload, context) -> context.server().execute(() -> ShopService.handle(context.player(), payload)));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ShopConfig.loadSettings();
            ShopConfig.loadShop(server.registryAccess());
        });
        // /reload: read the files again and show the change to players who have the shop open
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            ShopConfig.loadSettings();
            ShopConfig.loadShop(server.registryAccess());
            ShopService.refreshAll(server);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ShopCommands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ShopService.onLogin(handler.player));
    }
}
