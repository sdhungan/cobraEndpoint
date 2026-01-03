# Cobra Endpoint Structure

Cobra Endpoint Structure is a **GoLand plugin** that provides a Structure-like tool window for visualizing **Echo** HTTP routes in Go projects.

It parses Echo route definitions using GoLand’s PSI and presents them in a clean, hierarchical tree grouped by route groups. This makes it easier to understand and navigate routing logic in larger Go files or directories.

---

## Features

- Displays Echo **route groups** as tree nodes
- Lists endpoints under each group (GET, POST, PUT, PATCH, DELETE, etc.)
- Works on:
    - the currently open Go file
    - or an entire directory
- Routes are shown in **source order**
- Reflects **unsaved editor changes**
- Lightweight and fast (no custom indexing)

---

## How It Works

The plugin analyzes Go source files using GoLand’s PSI to detect:

- `Group(...)` calls, including nested groups
- HTTP method calls on group variables (for example `group.GET(...)`)

It resolves group prefixes, normalizes paths, and builds a structured tree view that mirrors how routes are defined in code.

---

## Usage

1. Open a Go file or directory containing Echo routes
2. Open the **Cobra Endpoint Structure** tool window
3. Browse route groups and endpoints in a structured tree

---

## Requirements

- **GoLand 2025.2 or newer**
- Projects using the **Echo** web framework

---

## License

MIT Licensed. Copyright © 2026 sdhungan  
See the [LICENSE](LICENSE) file for details.