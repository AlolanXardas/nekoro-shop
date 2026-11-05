package dev.xardas.nekoroshop.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import dev.xardas.nekoroshop.NekoroShop;
import dev.xardas.nekoroshop.client.ShopScreen;

/** NeoForge client events, connected to the shared client code. */
public final class NeoForgeClient {
    private NeoForgeClient() { }

    public static void init(IEventBus modBus) {
        modBus.addListener((RegisterMenuScreensEvent event) -> event.register(NekoroShop.MENU.get(), ShopScreen::new));
    }
}
