/*
 * Clearlagg - lag reduction for Paper servers
 * Copyright (C) 2026 LucasTHCR
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.empireblocks.clearlagg;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public class ClearlaggExpansion extends PlaceholderExpansion {
    private final Clearlagg plugin;

    public ClearlaggExpansion(Clearlagg plugin) {
        this.plugin = plugin;
    }

    public String getIdentifier() {
        return "clearlagg";
    }

    public String getAuthor() {
        return "EmpireBlocks";
    }

    public String getVersion() {
        return this.plugin.getDescription().getVersion();
    }

    public boolean persist() {
        return true;
    }

    public String onRequest(OfflinePlayer player, String params) {
        this.plugin.maybeAutoClearFromPlaceholder(params);
        String key = params.toLowerCase(Locale.ROOT);
        switch (key) {
            case "tps":
                return String.format("%.2f", this.plugin.getTpsTracker().getTps1m());
            case "tps_colored":
                return this.plugin.colorizeTps(this.plugin.getTpsTracker().getTps1m());
            case "ram_used":
                return String.valueOf((long)this.plugin.getUsedRamMb());
            case "ram_max":
                return String.valueOf((long)this.plugin.getMaxRamMb());
            case "ram_percent":
                return String.format("%.1f", this.plugin.getRamPercent());
            case "entities_world":
                if (player != null && player.isOnline() && player.getPlayer() != null) {
                    return String.valueOf(player.getPlayer().getWorld().getEntities().size());
                }

                return "0";
            case "entities_total":
                return String.valueOf(this.plugin.getTotalEntityCount());
            case "chunks_loaded":
                if (player != null && player.isOnline() && player.getPlayer() != null) {
                    return String.valueOf(player.getPlayer().getWorld().getLoadedChunks().length);
                }

                return "0";
            case "last_clear_amount":
                return String.valueOf(this.plugin.getLastClearAmount());
            case "halted":
                return String.valueOf(this.plugin.getHaltManager().isHalted());
            default:
                return null;
        }
    }
}
