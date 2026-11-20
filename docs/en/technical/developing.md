---
title: Developing
---

# Building

Requires JDK 21 or newer. Gradle downloads Minecraft, the mappings, Fabric
API, and the JDK 25 toolchain the 26.x builds target, on demand.

```sh
./gradlew :1.21.11:build
./gradlew :paper:build
./gradlew :paper:runServer
```

Swap `1.21.11` for any version from the
[supported versions table](../getting-started/install.md#Supported-versions).
Jars are written to `versions/<minecraft-version>/build/libs/` and the
standalone Paper plugin to `paper/build/libs/`. `:paper:runServer` downloads
a local Paper 1.21.1 development server and installs the freshly built
plugin; accept the Minecraft EULA in `paper/run/eula.txt` and run the task
again.

# For mod developers

Conflux Map ships a client-side API (waypoints, map-data queries, custom map
markers, actions, and events). It is experimental and one dependency covers
every supported Minecraft version. See [Public API](api.md) for the setup
and usage guide.

# Docs preview and the config reference

This wiki builds with MkDocs. To preview it locally:

```sh
python -m venv .venv
.venv/bin/pip install -r requirements-docs.txt
.venv/bin/mkdocs serve
```

`mkdocs build --strict` runs in CI; broken links fail the build. The
[Configuration](../config/index.md) page is generated from the mod's lang
files — after changing config labels or tooltips, regenerate it with:

```sh
python tools/gen_config_ref.py
```

and commit both generated pages. Translators follow the terminology in the
[i18n glossary](i18n-glossary.md).

See also: [Network protocol](protocol.md),
[Prediction quality](prediction-quality.md)
