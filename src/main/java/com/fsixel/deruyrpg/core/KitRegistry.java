package com.fsixel.deruyrpg.core;

import com.fsixel.deruyrpg.DeruyRPG;
import com.fsixel.deruyrpg.api.WeaponKit;
import com.fsixel.deruyrpg.util.Items;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class KitRegistry {

    private final DeruyRPG plugin;
    private final Map<String, WeaponKit> kits = new LinkedHashMap<>();

    public KitRegistry(DeruyRPG plugin) {
        this.plugin = plugin;
    }

    public void register(WeaponKit kit) {
        if (kits.containsKey(kit.id())) {
            throw new IllegalStateException("중복 무기 id: " + kit.id());
        }
        kits.put(kit.id(), kit);
        if (kit instanceof Listener listener) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
    }

    public @Nullable WeaponKit get(String id) {
        return kits.get(id);
    }

    public @Nullable WeaponKit find(@Nullable ItemStack item) {
        String id = Items.kitId(item);
        return id == null ? null : kits.get(id);
    }

    public Collection<WeaponKit> all() {
        return Collections.unmodifiableCollection(kits.values());
    }
}
