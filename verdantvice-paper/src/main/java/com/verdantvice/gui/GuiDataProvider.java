package com.verdantvice.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface GuiDataProvider {
    List<MenuEntry> entries(Player player, MenuType type, GuiContext context);

    default GuiContext resolveContext(Player player, MenuType type, GuiContext requested) {
        return requested;
    }

    default Map<String, ?> titlePlaceholders(
            Player player,
            MenuType type,
            GuiContext context
    ) {
        return Map.of();
    }

    default String emptyMessageKey(MenuType type, GuiContext context) {
        return "gui.empty.generic";
    }

    default Optional<String> accessDeniedKey(
            Player player,
            MenuType type,
            GuiContext context
    ) {
        return Optional.empty();
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
