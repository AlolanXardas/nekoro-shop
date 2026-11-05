package dev.xardas.nekoroshop.neoforge;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.CreativeModeTabSearchRegistry;
import net.neoforged.neoforge.network.PacketDistributor;

import dev.xardas.nekoroshop.client.ClientPlatform;

public final class NeoForgeClientPlatform implements ClientPlatform {
    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    /** What NeoForge's creative screen does after filling the tabs (every tab with a search bar has its own search). */
    @Override
    public void updateCreativeSearch(HolderLookup.Provider registries) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return;
        SessionSearchTrees trees = connection.searchTrees();
        CreativeModeTabs.allTabs().stream().filter(CreativeModeTab::hasSearchBar).forEach(tab -> {
            List<ItemStack> items = List.copyOf(tab.getDisplayItems());
            trees.updateCreativeTooltips(registries, items, CreativeModeTabSearchRegistry.getNameSearchKey(tab));
            trees.updateCreativeTags(items, CreativeModeTabSearchRegistry.getTagSearchKey(tab));
        });
    }
}
