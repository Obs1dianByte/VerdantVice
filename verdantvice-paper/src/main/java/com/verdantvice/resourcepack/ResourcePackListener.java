package com.verdantvice.resourcepack;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Objects;

public final class ResourcePackListener implements Listener {
    private final ResourcePackService resourcePack;

    public ResourcePackListener(ResourcePackService resourcePack) {
        this.resourcePack = Objects.requireNonNull(resourcePack, "resourcePack");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (resourcePack.sendOnJoin()) {
            resourcePack.send(event.getPlayer());
        }
    }
}
