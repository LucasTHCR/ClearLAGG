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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public class SamplerManager {
    private BukkitTask tickTask;
    private BukkitTask memoryTask;
    private volatile boolean tickSampling;
    private volatile boolean memorySampling;

    public boolean isTickSampling() {
        return this.tickSampling;
    }

    public boolean isMemorySampling() {
        return this.memorySampling;
    }

    public void sampleTicks(Plugin plugin, int ticks, boolean raw, Consumer<List<Long>> onDone) {
        if (!this.tickSampling && ticks > 0) {
            this.tickSampling = true;
            List<Long> samples = new ArrayList<>(ticks);
            long[] last = new long[]{System.nanoTime()};
            this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                long now = System.nanoTime();
                samples.add(now - last[0]);
                last[0] = now;
                if (samples.size() >= ticks) {
                    this.tickTask.cancel();
                    this.tickSampling = false;
                    onDone.accept(samples);
                }
            }, 1L, 1L);
        }
    }

    public void sampleMemory(Plugin plugin, int seconds, int samplesPerSecond, Consumer<SamplerManager.MemorySampleResult> onDone) {
        if (!this.memorySampling && seconds > 0) {
            this.memorySampling = true;
            long periodTicks = Math.max(1L, 20L / (long)Math.max(1, samplesPerSecond));
            int totalSamples = Math.max(1, seconds * samplesPerSecond);
            List<Long> heapSamplesMb = new ArrayList<>();
            int[] count = new int[]{0};
            long baseCollections = 0L;
            long baseTime = 0L;

            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                baseCollections += Math.max(0L, bean.getCollectionCount());
                baseTime += Math.max(0L, bean.getCollectionTime());
            }

            long finalBaseCollections = baseCollections;
            long finalBaseTime = baseTime;
            this.memoryTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                Runtime rt = Runtime.getRuntime();
                heapSamplesMb.add((rt.totalMemory() - rt.freeMemory()) / 1024L / 1024L);
                count[0]++;
                if (count[0] >= totalSamples) {
                    this.memoryTask.cancel();
                    this.memorySampling = false;
                    long endCollections = 0L;
                    long endTime = 0L;

                    for (GarbageCollectorMXBean beanx : ManagementFactory.getGarbageCollectorMXBeans()) {
                        endCollections += Math.max(0L, beanx.getCollectionCount());
                        endTime += Math.max(0L, beanx.getCollectionTime());
                    }

                    long min = Long.MAX_VALUE;
                    long max = 0L;
                    long sum = 0L;

                    for (long v : heapSamplesMb) {
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                        sum += v;
                    }

                    SamplerManager.MemorySampleResult result = new SamplerManager.MemorySampleResult();
                    result.minMb = heapSamplesMb.isEmpty() ? 0L : min;
                    result.maxMb = max;
                    result.avgMb = heapSamplesMb.isEmpty() ? 0.0 : (double)sum / (double)heapSamplesMb.size();
                    result.gcCount = endCollections - finalBaseCollections;
                    result.gcTimeMs = endTime - finalBaseTime;
                    onDone.accept(result);
                }
            }, periodTicks, periodTicks);
        }
    }

    public static class MemorySampleResult {
        public long minMb;
        public long maxMb;
        public double avgMb;
        public long gcCount;
        public long gcTimeMs;
    }
}
