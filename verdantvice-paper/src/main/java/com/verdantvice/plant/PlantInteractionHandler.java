package com.verdantvice.plant;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

@FunctionalInterface
public interface PlantInteractionHandler {
    void interact(Player player, UUID plantId, EquipmentSlot hand);
}
