#!/bin/sh
# Builds the GitHub Pages site for Cantino into a local directory:
#
#   <out>/index.html    landing page (links to GitHub, guide, API, Maven)
#   <out>/maven/...     static Maven repository: io/github/mvexel/cantino/<version>/
#                       (AAR, POM, Gradle module metadata, sources jar, checksums)
#   <out>/api/...       Dokka HTML API reference (public API only)
#   <out>/.nojekyll     serve files as is
#
# Apps then consume the AAR with
#   maven { url = uri("https://mvexel.github.io/cantino/maven") }
#
# This script NEVER commits or pushes. With --worktree it also copies the
# site into a local `gh-pages` git worktree (created as an orphan branch when
# none exists) and prints the commands to commit and push it yourself.
#
# Usage: scripts/publish-pages.sh [--out DIR] [--worktree [DIR]] [--skip-native]
#   --out DIR        site directory (default: build/pages)
#   --worktree [DIR] also sync into a gh-pages worktree (default: build/gh-pages)
#   --skip-native    reuse target/android/*/libcantino.so instead of rebuilding
#                    them with scripts/build-android.sh
#
# Needs: the Android toolchain of HANDOFF.md (Rust 1.99 + Android targets,
# NDK r29, Android SDK, mise for the JDK).
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
out="$root/build/pages"
worktree=""
native=1
while [ "$#" -gt 0 ]; do
    case "$1" in
        --out) out=$2; shift 2 ;;
        --worktree)
            if [ "$#" -gt 1 ] && [ "${2#--}" = "$2" ]; then worktree=$2; shift 2
            else worktree="$root/build/gh-pages"; shift; fi ;;
        --skip-native) native=0; shift ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done
case "$out" in /*) ;; *) out="$PWD/$out" ;; esac
case "$worktree" in ""|/*) ;; *) worktree="$PWD/$worktree" ;; esac

version=$(sed -n 's/^version = "\(.*\)"/\1/p' "$root/Cargo.toml" | head -n 1)
[ -n "$version" ] || { echo "no version in Cargo.toml" >&2; exit 2; }
echo "Cantino $version -> $out"

# 1. Native libraries (the AAR packages target/android/<ABI>/libcantino.so).
if [ "$native" = 1 ]; then
    "$root/scripts/build-android.sh"
fi
for abi in arm64-v8a x86_64; do
    [ -s "$root/target/android/$abi/libcantino.so" ] || {
        echo "missing target/android/$abi/libcantino.so; run scripts/build-android.sh" >&2
        exit 2
    }
done

# 2. Start from the published Maven repo when a gh-pages worktree has one, so
#    maven-metadata.xml keeps listing earlier versions.
rm -rf "$out"
mkdir -p "$out"
if [ -n "$worktree" ] && [ -d "$worktree/maven" ]; then
    cp -a "$worktree/maven" "$out/maven"
fi

# 3. AAR into the Maven layout + Dokka HTML.
(cd "$root/android" && mise exec -- ./gradlew --console=plain \
    -PpagesRepo="$out/maven" \
    :cantino:publishReleasePublicationToPagesRepository \
    :cantino:dokkaGeneratePublicationHtml)
cp -a "$root/android/cantino/build/dokka/html" "$out/api"
touch "$out/.nojekyll"

# 4. Landing page.
cat > "$out/index.html" <<EOF
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Cantino</title>
<meta name="description" content="Cantino, an offline OpenStreetMap SDK for Android.">
<style>
  :root { color-scheme: light dark; --fg: #1d1d1f; --muted: #5f6368; --bg: #fbfaf7; --code: #efede8; --link: #0b57d0; }
  @media (prefers-color-scheme: dark) { :root { --fg: #e8e6e3; --muted: #a8a49c; --bg: #161616; --code: #262624; --link: #8ab4f8; } }
  body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.55 system-ui, sans-serif; }
  main { max-width: 44rem; margin: 0 auto; padding: 2.5rem 16px; }
  h1 { margin: 0 0 .25rem; font-size: 2rem; }
  p.tag { margin: 0 0 1.5rem; color: var(--muted); }
  a { color: var(--link); }
  pre { background: var(--code); padding: .9rem 1rem; border-radius: 6px; overflow-x: auto; font-size: .9rem; }
  ul { padding-left: 1.2rem; }
  footer { margin-top: 2.5rem; color: var(--muted); font-size: .85rem; }
</style>
</head>
<body>
<main>
<h1>Cantino</h1>
<p class="tag">An offline OpenStreetMap SDK for Android. Version $version.</p>
<p>Download OpenStreetMap data and an optional PMTiles basemap for an area your app
chooses, keep them on the device, and query them with no network. In 1502 Alberto
Cantino smuggled a copy of Portugal's secret master map out of Lisbon; this is a copy
of the master map you carry away.</p>
<ul>
  <li><a href="https://github.com/mvexel/cantino">Source, README and quickstart</a></li>
  <li><a href="https://github.com/mvexel/cantino/tree/main/docs/guide">Guide</a></li>
  <li><a href="api/">API reference</a></li>
  <li><a href="maven/io/github/mvexel/cantino/">Maven repository</a>
      (<a href="https://github.com/mvexel/cantino/blob/main/CHANGELOG.md">changelog</a>)</li>
</ul>
<pre><code>// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mvexel.github.io/cantino/maven") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.mvexel:cantino:$version")
}</code></pre>
<footer>Apache-2.0. Map data &copy; OpenStreetMap contributors (ODbL); apps that show it must say so.
"OpenStreetMap" is used descriptively; Cantino is not affiliated with the OpenStreetMap Foundation.</footer>
</main>
</body>
</html>
EOF

# Maven directory listings: GitHub Pages serves files, not directory indexes,
# so give each Maven directory a plain index.html for humans browsing it.
find "$out/maven" -type d | while read -r dir; do
    {
        echo '<!doctype html><meta charset="utf-8"><title>Cantino Maven repository</title><pre>'
        for entry in "$dir"/*; do
            name=$(basename "$entry")
            [ "$name" = index.html ] && continue
            [ -d "$entry" ] && name="$name/"
            echo "<a href=\"$name\">$name</a>"
        done
        echo '</pre>'
    } > "$dir/index.html"
done

echo
echo "Site written to $out:"
(cd "$out" && find maven -name "*.aar" -o -name "*.pom" | sort && echo "api/index.html" && echo "index.html")

# 5. Optional local gh-pages worktree (never pushed from here).
if [ -n "$worktree" ]; then
    if [ ! -e "$worktree/.git" ]; then
        if git -C "$root" show-ref --verify --quiet refs/heads/gh-pages; then
            git -C "$root" worktree add "$worktree" gh-pages
        elif git -C "$root" show-ref --verify --quiet refs/remotes/origin/gh-pages; then
            git -C "$root" worktree add -b gh-pages "$worktree" origin/gh-pages
        else
            git -C "$root" worktree add --orphan -b gh-pages "$worktree"
        fi
    fi
    # Replace everything except .git with the new site.
    find "$worktree" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
    cp -a "$out/." "$worktree/"
    echo
    echo "gh-pages worktree updated (nothing committed): $worktree"
fi

cat <<EOF

Next steps (run them yourself; this script never pushes):

  # 1. Commit and push the site to the gh-pages branch
EOF
if [ -n "$worktree" ]; then
    cat <<EOF
  cd "$worktree"
  git add -A && git commit -m "Publish Cantino $version"
  git push -u origin gh-pages
EOF
else
    echo "  (rerun with --worktree to stage it in a local gh-pages worktree)"
fi
cat <<EOF

  # 2. Tag the release (API reference source links point at v$version)
  git -C "$root" tag v$version && git -C "$root" push origin v$version

  # 3. Enable GitHub Pages from gh-pages (once)
  gh api -X POST repos/mvexel/cantino/pages -f source[branch]=gh-pages -f source[path]=/

  # 4. Make the repository public (once)
  gh repo edit mvexel/cantino --visibility public --accept-visibility-change-consequences

Then check https://mvexel.github.io/cantino/maven/io/github/mvexel/cantino/$version/cantino-$version.pom
EOF
