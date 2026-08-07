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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public class UpdateChecker {
    private static final String MODRINTH_PROJECT_ID = "zASITjNd";
    private static final String API_URL = "https://api.modrinth.com/v2/project/zASITjNd/version";
    private static final String PROJECT_URL = "https://modrinth.com/project/zASITjNd";
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]+)\"");
    private BukkitTask task;
    private volatile String latestVersion;
    private volatile String lastAnnouncedVersion;

    public void start(Clearlagg plugin) {
        this.stop();
        if (plugin.getConfig().getBoolean("settings.check-for-updates", true)) {
            long intervalMinutes = Math.max(1L, plugin.getConfig().getLong("settings.update-check-interval-minutes", 30L));
            long ticks = intervalMinutes * 60L * 20L;
            this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> this.performCheck(plugin), 0L, ticks);
        }
    }

    public void stop() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
    }

    private void performCheck(Clearlagg plugin) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://api.modrinth.com/v2/project/zASITjNd/version"))
                .header("User-Agent", "Clearlagg/" + plugin.getDescription().getVersion() + " (update-checker)")
                .timeout(Duration.ofSeconds(10L)).GET().build();
            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return;
            }

            Matcher matcher = VERSION_NUMBER_PATTERN.matcher(response.body());
            if (!matcher.find()) {
                return;
            }

            String newest = matcher.group(1);
            String current = plugin.getDescription().getVersion();
            boolean available = !newest.equalsIgnoreCase(current);
            this.latestVersion = available ? newest : null;
            if (available && !newest.equalsIgnoreCase(this.lastAnnouncedVersion)) {
                this.lastAnnouncedVersion = newest;
                Bukkit.getScheduler().runTask(plugin, () -> this.announce(plugin, newest, current));
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.FINE, "Update check failed (non-fatal): " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void announce(Clearlagg plugin, String newest, String current) {
        String message = plugin.getPrefix()
            + ChatColor.translateAlternateColorCodes(
                '&', "&eA new version is available: &a" + newest + " &7(running &c" + current + "&7). &7Download: &bhttps://modrinth.com/project/zASITjNd"
            );
        plugin.getLogger().info(ChatColor.stripColor(message));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("lagg.notify")) {
                p.sendMessage(message);
            }
        }
    }

    public String getLatestVersion() {
        return this.latestVersion;
    }

    public boolean isUpdateAvailable() {
        return this.latestVersion != null;
    }
}
