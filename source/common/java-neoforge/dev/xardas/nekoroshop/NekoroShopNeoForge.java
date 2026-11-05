package dev.xardas.nekoroshop;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.RegisterEvent;

import dev.xardas.nekoroshop.client.ClientShop;
import dev.xardas.nekoroshop.neoforge.NeoForgeClient;

/** NeoForge entry point: registers the block, item and window and connects NeoForge events to the shared code. */
@Mod(NekoroShop.MOD_ID)
public final class NekoroShopNeoForge {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(NekoroShop.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(NekoroShop.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, NekoroShop.MOD_ID);

    static {
        DeferredBlock<Block> block = BLOCKS.register(NekoroShop.BLOCK_NAME, NekoroShop::createBlock);
        NekoroShop.BLOCK = block;
        NekoroShop.ITEM = ITEMS.register(NekoroShop.BLOCK_NAME, () -> NekoroShop.createItem(block.get()));
        NekoroShop.CATALOG = ITEMS.register(NekoroShop.CATALOG_NAME, NekoroShop::createCatalog);
        NekoroTab.ICON = ITEMS.register(NekoroTab.ICON_NAME, () -> new net.minecraft.world.item.Item(new net.minecraft.world.item.Item.Properties()));
        NekoroShop.MENU = MENUS.register(NekoroShop.MENU_NAME, NekoroShop::createMenu);
    }

    public NekoroShopNeoForge(IEventBus modBus) {
        ShopConfig.loadSettings();
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        MENUS.register(modBus);
        modBus.addListener((RegisterEvent event) -> event.register(Registries.CREATIVE_MODE_TAB, helper -> {
            if (!NekoroTab.registered()) {
                helper.register(NekoroTab.KEY, CreativeModeTab.builder().title(Component.translatable(NekoroTab.TITLE)).icon(NekoroTab::icon).build());
            }
        }));
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == NekoroTab.KEY) {
                event.accept(NekoroShop.ITEM.get());
                event.accept(NekoroShop.CATALOG.get());
            }
        });
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
                .playToClient(Payloads.Rules.TYPE, Payloads.Rules.STREAM_CODEC,
                        (Payloads.Rules payload, IPayloadContext context) -> context.enqueueWork(() -> ClientShop.onRules(payload)))
                .playToClient(Payloads.Balance.TYPE, Payloads.Balance.STREAM_CODEC,
                        (Payloads.Balance payload, IPayloadContext context) -> context.enqueueWork(() -> ClientShop.onBalance(payload)))
                .playToClient(Payloads.Market.TYPE, Payloads.Market.STREAM_CODEC,
                        (Payloads.Market payload, IPayloadContext context) -> context.enqueueWork(() -> ClientShop.onMarket(payload)))
                .playToClient(Payloads.Notice.TYPE, Payloads.Notice.STREAM_CODEC,
                        (Payloads.Notice payload, IPayloadContext context) -> context.enqueueWork(() -> ClientShop.onNotice(payload)))
                .playToServer(Payloads.Trade.TYPE, Payloads.Trade.STREAM_CODEC,
                        (Payloads.Trade payload, IPayloadContext context) -> context.enqueueWork(() -> {
                            if (context.player() instanceof ServerPlayer player) ShopService.handle(player, payload);
                        })));

        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerAboutToStartEvent event) -> {
            ShopConfig.loadSettings();
            ShopConfig.loadShop(event.getServer().registryAccess());
        });
        // /reload: read the files again and show the change to players who have the shop open
        bus.addListener((OnDatapackSyncEvent event) -> {
            if (event.getPlayer() != null) return;
            ShopConfig.loadSettings();
            ShopConfig.loadShop(event.getPlayerList().getServer().registryAccess());
            ShopService.refreshAll(event.getPlayerList().getServer());
        });
        bus.addListener((RegisterCommandsEvent event) -> ShopCommands.register(event.getDispatcher()));
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) ShopService.onLogin(player);
        });

        if (FMLEnvironment.dist.isClient()) NeoForgeClient.init(modBus);
    }
}
