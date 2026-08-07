# Changelog

## 1.1-RELEASE

### Changed

- The `tps-meter` section no longer ships an enabled trigger. Previously the
  bundled `critical-tps` trigger ran a `say` command, which broadcast a TPS
  warning to every player on the server whenever the server dipped below 15 TPS.
  Triggers are now opt-in.
- Clearlagg is now open source under the GNU General Public License v3.0.
  Source: https://github.com/LucasTHCR/ClearLAGG

### Fixed

- PlaceholderAPI placeholders were lowercased using the system default locale,
  which broke them on servers running a Turkish locale. They now use
  `Locale.ROOT`.

### Note for existing installs

Bukkit does not overwrite an existing `config.yml` on update. If you installed
1.0-RELEASE, remove the `critical-tps` entry under `tps-meter.triggers` in
`plugins/Clearlagg/config.yml` by hand and run `/lagg reload`, otherwise the
broadcast stays active.

## 1.0-RELEASE

Initial release.
