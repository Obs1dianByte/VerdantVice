package com.verdantvice.trade;

import com.verdantvice.config.ConfigManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

public final class EconomyService {
    private final JavaPlugin plugin;
    private final ConfigManager configs;
    private Object vaultEconomy;
    private Method vaultHas;
    private Method vaultWithdraw;
    private Method vaultDeposit;

    public EconomyService(JavaPlugin plugin, ConfigManager configs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configs = Objects.requireNonNull(configs, "configs");
    }

    public void detect() {
        vaultEconomy = null;
        boolean requested = configs.get("economy.yml").getBoolean("vault.enabled", false)
                || "VAULT".equalsIgnoreCase(
                configs.get("economy.yml").getString("provider", "EMERALD")
        );
        if (!requested || !configs.get("integrations.yml").getBoolean("vault", true)
                || !plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            return;
        }
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings({"rawtypes", "unchecked"})
            RegisteredServiceProvider<?> registration =
                    plugin.getServer().getServicesManager().getRegistration((Class) economyClass);
            if (registration == null) {
                return;
            }
            vaultEconomy = registration.getProvider();
            vaultHas = economyClass.getMethod("has", org.bukkit.OfflinePlayer.class, double.class);
            vaultWithdraw = economyClass.getMethod(
                    "withdrawPlayer",
                    org.bukkit.OfflinePlayer.class,
                    double.class
            );
            vaultDeposit = economyClass.getMethod(
                    "depositPlayer",
                    org.bukkit.OfflinePlayer.class,
                    double.class
            );
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().log(Level.WARNING, "Vault economy adapter could not start", exception);
            vaultEconomy = null;
        }
    }

    public String providerName() {
        return vaultEconomy == null ? "Emerald" : "Vault";
    }

    public boolean has(Player player, int amount) {
        requireMainThread();
        if (amount < 0) {
            throw new IllegalArgumentException("amount cannot be negative");
        }
        if (vaultEconomy != null) {
            try {
                return (boolean) vaultHas.invoke(vaultEconomy, player, (double) amount);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Vault balance check failed", exception);
            }
        }
        return countEmeralds(player) >= amount;
    }

    public boolean withdraw(Player player, int amount) {
        requireMainThread();
        if (amount < 0) {
            throw new IllegalArgumentException("amount cannot be negative");
        }
        if (!has(player, amount)) {
            return false;
        }
        if (vaultEconomy != null) {
            return transactionSucceeded(invoke(vaultWithdraw, player, (double) amount));
        }
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != Material.EMERALD) {
                continue;
            }
            int remove = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - remove);
            contents[slot] = stack.getAmount() == 0 ? null : stack;
            remaining -= remove;
        }
        player.getInventory().setStorageContents(contents);
        return remaining == 0;
    }

    public void deposit(Player player, int amount) {
        requireMainThread();
        if (amount < 0) {
            throw new IllegalArgumentException("amount cannot be negative");
        }
        if (vaultEconomy != null) {
            if (!transactionSucceeded(invoke(vaultDeposit, player, (double) amount))) {
                throw new IllegalStateException("Vault rejected the deposit");
            }
            return;
        }
        int remaining = amount;
        while (remaining > 0) {
            int stackSize = Math.min(Material.EMERALD.getMaxStackSize(), remaining);
            Map<Integer, ItemStack> overflow = player.getInventory()
                    .addItem(new ItemStack(Material.EMERALD, stackSize));
            overflow.values().forEach(stack ->
                    player.getWorld().dropItemNaturally(player.getLocation(), stack));
            remaining -= stackSize;
        }
    }

    private int countEmeralds(Player player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == Material.EMERALD) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(vaultEconomy, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Vault transaction failed", exception);
        }
    }

    private boolean transactionSucceeded(Object response) {
        try {
            return (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unrecognized Vault response", exception);
        }
    }

    private void requireMainThread() {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Economy operations must run on the server thread");
        }
    }
}
