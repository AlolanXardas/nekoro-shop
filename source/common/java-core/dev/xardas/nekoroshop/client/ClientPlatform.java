package dev.xardas.nekoroshop.client;

import net.minecraft.core.HolderLookup;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import dev.xardas.nekoroshop.Platform;

/** Client-side things that differ between NeoForge and Fabric. Only touched from client code. */
public interface ClientPlatform {
    ClientPlatform INSTANCE = Platform.load(ClientPlatform.class);

    void sendToServer(CustomPacketPayload payload);

    /**
     * Fills the creative inventory's search with the tabs' items. Needed after this mod filled the creative tabs
     * itself: the creative screen then sees nothing new and would leave its search empty.
     */
    void updateCreativeSearch(HolderLookup.Provider registries);
}
