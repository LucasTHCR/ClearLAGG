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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.FileConfiguration;

public class ModuleAdmin {
    private static final String[] MODULES = new String[]{
        "entity-clear",
        "kill-mobs",
        "limiters",
        "spawner-limiter",
        "mob-egg-limiter",
        "breeding-limiter",
        "item-livetime",
        "tnt",
        "chunk-analysis",
        "chunk-unloader",
        "ai-limiter",
        "tps-meter",
        "gc-monitor",
        "tick-sampler",
        "memory-sampler",
        "profiler",
        "halt"
    };
    private final Map<String, Boolean> runtimeOverrides = new ConcurrentHashMap<>();
    private FileConfiguration cfg;

    public void load(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public boolean isEnabled(String module) {
        Boolean override = this.runtimeOverrides.get(module);
        if (override != null) {
            return override;
        }

        return this.cfg == null ? true : this.cfg.getBoolean(module + ".enabled", true);
    }

    public void setEnabled(String module, boolean enabled) {
        this.runtimeOverrides.put(module, enabled);
    }

    public void clearOverride(String module) {
        this.runtimeOverrides.remove(module);
    }

    public static String[] moduleNames() {
        return (String[])MODULES.clone();
    }
}
