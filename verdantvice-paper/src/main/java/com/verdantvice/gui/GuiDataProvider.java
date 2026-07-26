package com.verdantvice.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public interface GuiDataProvider {
    List<MenuEntry> entries(Player player, MenuType type);

    default Map<String, ?> titlePlaceholders(Player player, MenuType type) {
        return Map.of();
    }

    record MenuEntry(
            Material icon,
            Component name,
            List<Component> lore,
            String action,
            boolean glowing
    ) {
        public MenuEntry {
            lore = List.copyOf(lore);
        }
    }
}
