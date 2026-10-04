-- pandoc Lua filter used by scripts/publish-pages.sh to render docs/guide/*.md
-- as the HTML site under /guide/.
--
-- Links:
--   sibling guide pages        foo.md#x        -> foo.html#x   (README.md -> index.html)
--   ../screenshots/...         kept (the script copies docs/screenshots to /screenshots/)
--   any other repo-relative    ../../X, ../research/Y -> https://github.com/mvexel/cantino/blob/main/<path>
-- Mermaid code blocks become <pre class="mermaid"> and load mermaid from jsdelivr.

local repo = "https://github.com/mvexel/cantino/blob/main/"
local base_dir = "docs/guide"

local function is_external(t)
  return t:match("^%a[%w+.-]*:") or t:sub(1, 1) == "#" or t:sub(1, 1) == "/"
end

-- Resolve a repo-relative path against docs/guide, collapsing "..".
local function resolve(path)
  local parts = {}
  for seg in (base_dir .. "/" .. path):gmatch("[^/]+") do
    if seg == ".." then table.remove(parts) elseif seg ~= "." then parts[#parts + 1] = seg end
  end
  return table.concat(parts, "/")
end

local function rewrite(target)
  if is_external(target) then return target end
  local path, anchor = target:match("^([^#]*)(#?.*)$")
  if path:match("^%.%./screenshots/") then return target end
  if not path:find("/", 1, true) and path:match("%.md$") then
    if path == "README.md" then path = "index.md" end
    return path:gsub("%.md$", ".html") .. anchor
  end
  return repo .. resolve(path) .. anchor
end

function Link(el)
  el.target = rewrite(el.target)
  return el
end

function Image(el)
  el.src = rewrite(el.src)
  return el
end

local has_mermaid = false
function CodeBlock(el)
  if el.classes:includes("mermaid") then
    has_mermaid = true
    local text = el.text:gsub("&", "&amp;"):gsub("<", "&lt;"):gsub(">", "&gt;")
    return pandoc.RawBlock("html", '<pre class="mermaid">' .. text .. "</pre>")
  end
end

function Pandoc(doc)
  if has_mermaid then
    local dark = '(window.matchMedia&&matchMedia("(prefers-color-scheme: dark)").matches)?"dark":"default"'
    doc.blocks:insert(pandoc.RawBlock("html",
      '<script type="module">import mermaid from "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs";' ..
      "mermaid.initialize({startOnLoad:true,theme:" .. dark .. "});</script>"))
  end
  return doc
end
