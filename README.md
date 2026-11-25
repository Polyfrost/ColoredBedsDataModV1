# ColoredBedsData

Fabric 26.3 client mod that visits every Hypixel Bed Wars map and records which bed color is on each side of the map, for the colored beds feature (`bed_locations.json`).

Needs the [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) mod installed, and MVP+ or higher since the map selector is only unlimited from that rank.

## Usage

- Join Hypixel and run `/coloredbedsdata start`.
- Progress is saved to `config/coloredbedsdata/progress.json`, so `start` resumes. `reset` clears it.
- When done (or with `/coloredbedsdata export`) it merges the results into the current upstream file and writes `config/coloredbedsdata/bed_locations.json`, listing every map that changed.
