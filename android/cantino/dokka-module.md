# Module Cantino

Cantino, an offline OpenStreetMap SDK for Android. It downloads OpenStreetMap data (and,
optionally, a PMTiles basemap) for an area your app chooses, keeps it on the device and
queries it with no network.

Start here:

- [lol.osm.cantino.AreaManager] downloads, refreshes and locates areas;
  observe progress as [lol.osm.cantino.AreaState].
- [lol.osm.cantino.OsmStore] opens a published area and answers lookups
  ([lol.osm.cantino.OsmId]) and queries ([lol.osm.cantino.Query]).
- [lol.osm.cantino.BasemapSource] chooses the optional offline basemap;
  [lol.osm.cantino.AreaInfo.pmtilesUrl] plugs it into MapLibre Native.

Guide, install instructions and quickstart: <https://github.com/mvexel/cantino>.

Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).

# Package lol.osm.cantino

The whole public API: area downloads ([AreaManager], [AreaState], [AreaConfig],
[BasemapSource]), the offline store ([OsmStore], [Query], [OsmObject]) and PMTiles
helpers ([PmtilesInfo]).
