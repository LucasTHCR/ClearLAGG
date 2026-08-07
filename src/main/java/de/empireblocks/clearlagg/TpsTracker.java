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

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class TpsTracker {
    private BukkitTask task;
    private long lastTick;
    private double tps5s = 20.0;
    private double tps1m = 20.0;
    private double tps5m = 20.0;

    public void start(Plugin plugin) {
        this.lastTick = System.nanoTime();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void tick() {
        long now = System.nanoTime();
        double elapsedSeconds = (double)(now - this.lastTick) / 1.0E9;
        this.lastTick = now;
        if (elapsedSeconds <= 0.0) {
            return;
        }

        double currentTps = Math.min(20.0, 1.0 / elapsedSeconds);
        this.tps5s = this.decay(this.tps5s, currentTps, elapsedSeconds, 5.0);
        this.tps1m = this.decay(this.tps1m, currentTps, elapsedSeconds, 60.0);
        this.tps5m = this.decay(this.tps5m, currentTps, elapsedSeconds, 300.0);
    }

    private double decay(double average, double current, double elapsedSeconds, double windowSeconds) {
        double alpha = 1.0 - Math.exp(-elapsedSeconds / windowSeconds);
        return average + alpha * (current - average);
    }

    public double getTps5s() {
        return this.tps5s;
    }

    public double getTps1m() {
        return this.tps1m;
    }

    public double getTps5m() {
        return this.tps5m;
    }
}
