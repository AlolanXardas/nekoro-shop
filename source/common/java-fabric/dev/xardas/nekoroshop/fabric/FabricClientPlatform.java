package dev.xardas.nekoroshop.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

import dev.xardas.nekoroshop.client.ClientPlatform;

public final class FabricClientPlatform implements ClientPlatform {
    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    /** What the creative screen does after filling the tabs. */
    @Override
    public void updateCreativeSearch(HolderLookup.Provider registries) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        SessionSearchTrees trees = connection.searchTrees();
        List<ItemStack> items = List.copyOf(CreativeModeTabs.searchTab().getDisplayItems());
        trees.updateCreativeTooltips(registries, items);
        trees.updateCreativeTags(items);
    }
}
