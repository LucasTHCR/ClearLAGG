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

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import org.bukkit.Bukkit;

public class GcWatcher {
    private final List<GarbageCollectorMXBean> beans = new ArrayList<>();
    private final List<NotificationListener> listeners = new ArrayList<>();

    public void start(Clearlagg plugin) {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                NotificationListener listener = (notification, handback) -> {
                    if ("com.sun.management.gc.notification".equals(notification.getType())) {
                        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData)notification.getUserData());
                        long duration = info.getGcInfo().getDuration();
                        String collectorName = info.getGcName();
                        Bukkit.getScheduler().runTask(plugin, () -> plugin.onGcEvent(collectorName, duration));
                    }
                };
                emitter.addNotificationListener(listener, null, null);
                this.beans.add(bean);
                this.listeners.add(listener);
            }
        }
    }

    public void stop() {
        for (int i = 0; i < this.beans.size(); i++) {
            try {
                ((NotificationEmitter)this.beans.get(i)).removeNotificationListener(this.listeners.get(i));
            } catch (Exception ignored) {
                // Listener was already gone; nothing left to detach.
            }
        }

        this.beans.clear();
        this.listeners.clear();
    }
}
