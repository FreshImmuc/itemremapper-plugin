# ItemRemapper Plugin

Ein leichtgewichtiges Spigot 1.21.1 Plugin, das Items beim Einsammeln oder Verschieben automatisch umbenennt.

## Features

- Automatisches Umbenennen von Items beim Einsammeln oder Verschieben ins Inventar
- Unterstützung für benutzerdefinierte Lore (mehrzeilige Beschreibung)
- Konfigurierbare Namens- und Lore-Zuweisung in `config.yml`
- Effiziente Verarbeitung: Nur neue Items ohne eigene Namen/Lore werden angepasst

## Voraussetzungen

- Paper 1.21.1 oder Folia 1.21.1
- Java 21+
- (Optional) ProtocolLib für Jukebox-Nachrichten

## Folia Support

Dieses Plugin unterstützt **vollständig Folia**, den multi-threaded Fork von Paper. Das Plugin nutzt:

- **Region Scheduler** für ortsgebundene Operationen (z.B. Jukebox-Handling)
- **Global Region Scheduler** für globale Tasks (z.B. periodische Scans)
- **Entity Scheduler** für entity-bezogene Operationen

Das Plugin erkennt automatisch, ob es auf Folia oder Paper läuft und verhält sich entsprechend. Keine zusätzliche Konfiguration erforderlich!

## Installation

1. Mit Maven bauen:
   ```bash
   mvn clean package
   ```
2. JAR aus `target/` ins Server-Pluginverzeichnis kopieren
3. Server starten und `config.yml` anpassen

## Konfiguration

Beispiel für `plugins/ItemRemapper/config.yml`:

```yaml
item-remaps:
  DIAMOND: "§b§lShiny Diamond"
  GOLD_INGOT:
    name: "§6Golden Bar"
    lore:
      - "§7Eine wertvolle Metallstange"
      - "§7aus purem Gold."
```

Verwende Bukkit Material-Namen:  
https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Material.html

Farbcodes: `§a` (grün), `§b` (aqua), `§c` (rot), usw.