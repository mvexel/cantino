# Module Cantino

Cantino, an offline OpenStreetMap SDK for Android. It downloads OpenStreetMap data (and,
optionally, a PMTiles basemap) for an area your app chooses, keeps it on the device and
queries it with no network.

Start here:

- [io.github.mvexel.cantino.AreaManager] downloads, refreshes and locates areas;
  observe progress as [io.github.mvexel.cantino.AreaState].
- [io.github.mvexel.cantino.OsmStore] opens a published area and answers lookups
  ([io.github.mvexel.cantino.OsmId]) and queries ([io.github.mvexel.cantino.Query]).
- [io.github.mvexel.cantino.BasemapSource] chooses the optional offline basemap;
  [io.github.mvexel.cantino.AreaInfo.pmtilesUrl] plugs it into MapLibre Native.

Guide, install instructions and quickstart: <https://github.com/mvexel/cantino>.

Map data © OpenStreetMap contributors, available under the Open Database License (ODbL).

# Package io.github.mvexel.cantino

The whole public API: area downloads ([AreaManager], [AreaState], [AreaConfig],
[BasemapSource]), the offline store ([OsmStore], [Query], [OsmObject]) and PMTiles
helpers ([PmtilesInfo], [ProtomapsBuilds]).
