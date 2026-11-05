package dev.xardas.nekoroshop.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;

import dev.xardas.nekoroshop.NekoroShop;
import dev.xardas.nekoroshop.Payloads;
import dev.xardas.nekoroshop.client.ClientShop;
import dev.xardas.nekoroshop.client.ShopScreen;

public final class NekoroShopFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MenuScreens.register(NekoroShop.MENU.get(), ShopScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Rules.TYPE, (payload, context) -> ClientShop.onRules(payload));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Balance.TYPE, (payload, context) -> ClientShop.onBalance(payload));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Market.TYPE, (payload, context) -> ClientShop.onMarket(payload));
        ClientPlayNetworking.registerGlobalReceiver(Payloads.Notice.TYPE, (payload, context) -> ClientShop.onNotice(payload));
    }
}
