# Future Roadmap & Feature Ideas (Post-v1.0.0)

This document tracks potential features and enhancements considered for future major releases of PlexVariables. None of these items are required for v1.0.0 stability and will be evaluated for future versions.

## Potential Post-v1.0 Features

### Remote Storage Adapters
- Support for MySQL / MariaDB storage backend for cross-server synchronization.
- Redis pub/sub support for instant global variable invalidation across multi-proxy networks.

### Advanced Scripting & Web Tools
- In-game GUI editor for inspecting and editing variables without modifying files.
- Web-based variable builder and validator tool.
- REST API endpoint for remote administration.
- JavaScript / Groovy scripting integration for complex variable logic.

### Developer API & Hooks
- Public Java API package (`dev.plex.plexvariables.api`) for third-party plugin integration.
- Custom Event hooks (`PlexVariableChangeEvent`, `PlexVariableResolveEvent`).
