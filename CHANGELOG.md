# Changelog

## 1.1-RELEASE

### Changed

- The `tps-meter` section no longer ships an enabled trigger. Previously the
  bundled `critical-tps` trigger ran a `say` command, which broadcast a TPS
  warning to every player on the server whenever the server dipped below 15 TPS.
  Triggers are now opt-in.
- Clearlagg is now open source under the GNU General Public License v3.0.
  Source: https://github.com/LucasTHCR/ClearLAGG
- Every player-facing text now comes from `lang/messages_<language>.yml`. The
  clear and kill-mobs broadcasts, the spawner, mob-egg and breeding denial
  messages, the GC pause warning and the halt/resume broadcasts used to be read
  from `config.yml`, which only ever held English. Reword them in the language
  file from now on; the corresponding `config.yml` keys are gone.

### Fixed

- Output is no longer half English on a translated server. With
  `settings.language` set to anything but English, the messages listed above
  stayed English while the rest of the plugin was translated.
- Filled in fifteen messages that only existed in English and German. The other
  ten languages silently fell back to English for command usage, sampler
  results, profiler output and several errors.
- Messages added in a new version now resolve on servers that already have a
  language file. Bukkit never overwrites an existing `lang/messages_*.yml`, and
  the fallback pointed at the English file on disk, which was equally outdated,
  so a new key printed `[Missing message: ...]`. Defaults now come from the copy
  inside the jar, in the configured language.
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
