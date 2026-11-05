package dev.xardas.nekoroshop;

import java.nio.file.Path;
import java.util.ServiceLoader;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** The few things that differ between NeoForge and Fabric. Each loader project provides one implementation. */
public interface Platform {
    Platform INSTANCE = load(Platform.class);

    static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, type.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + type.getSimpleName() + " for this mod loader"));
    }

    Path configDir();

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    boolean isModLoaded(String modId);
}
