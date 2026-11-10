# Contributing to Conflux Map

**English** | [简体中文](CONTRIBUTING_CN.md)

Thanks for your interest in improving Conflux Map! This guide covers the practical parts of contributing: getting a build running, the rules changes are reviewed against, and how to submit a pull request.

## Ways to contribute

- **Bug and compatibility reports** — open an issue using a template; they ask for exactly what triage needs. Templates are available in English and Chinese.
- **Feature requests** — please open an issue and discuss the approach before writing code.
- **Code fixes and features** — see the rest of this guide.
- **Translations** — language files live in `src/main/resources/assets/confluxmap/lang/` (currently `en_us.json` and `zh_cn.json`); keep player-facing terminology consistent with [`docs/i18n-glossary.md`](docs/i18n-glossary.md).

For anything non-trivial, open an issue first, and keep each pull request focused on one issue or one change.

## Setting up

You need Git and JDK 21 or newer. Gradle downloads Minecraft, the mappings, Fabric API, and the JDK 25 toolchain the 26.x builds target, on demand.

```sh
git clone --recursive https://github.com/Conflux-Union/conflux-map
cd conflux-map
./gradlew :1.17.1:build
```

The clone must be recursive: `native/cubiomes` is a git submodule. If you cloned without it, run `git submodule update --init`.

## Repository layout

| Path | What lives there |
| --- | --- |
| `common/` | Minecraft-free domain code: map stores, tile composition, waypoints, network protocol. Plain Java 16. |
| `src/` | The Fabric client and server implementation, written against Minecraft 1.17.1 and preprocessed into every other version. |
| `api/` | The experimental client-side API module (see [`docs/api.md`](docs/api.md)). |
| `paper/` | The standalone Paper plugin. |
| `versions/` | One Gradle subproject per supported Minecraft version. `versions/mainProject` names the source version; every other version is generated from `src/`. |
| `native/` | The cubiomes git submodule and the prebuilt prediction binaries. |
| `docs/` | Developer docs: the API guide, Paper companion notes, the translation glossary. |

`1.17.1` is the source project; swap it for any other version in the commands below to build or test that version from the same source.

## Everyday commands

```sh
./gradlew :1.17.1:build        # assemble the mod jar (no verification)
./gradlew :1.17.1:test         # shared-module and Fabric tests
./gradlew :common:test         # core tests only
./gradlew :paper:check         # verify the Paper plugin
./gradlew :1.17.1:check        # full verification for one version
./gradlew :1.17.1:runClient    # dev client
./gradlew :1.17.1:runServer    # dev server
```

Notes:

- There is no formatter or lint task; match the style of the surrounding code.
- `build` does not compile `src/gametest`; it is compiled and run by `check` on Minecraft 1.21.4 and newer. After renaming a type shared with the gametests, `./gradlew :1.21.4:compileGametestJava` gives a fast signal.

## Rules changes are reviewed against

1. **`common/` stays Minecraft-free.** No Minecraft, Fabric, Bukkit, mixin, or LWJGL imports there, and no preprocessor directives. Platform access belongs in `src/main` (Fabric) or `paper/`.
2. **Preprocessor directives belong only in `src/main`.** The other version projects are generated from it; never edit anything under `versions/*/build/`.
3. **One source, many versions.** A change to `src/main` ships to every supported version at once. Build and test the versions your change can affect, especially when touching version-gated code.
4. **`common/` and `api/` compile as Java 16** — don't use newer language features there.
5. **Mixins are registered in `src/main/resources/confluxmap.mixins.json`**, which is expanded per version at build time; there are no per-version copies to keep in sync.
6. **`native/cubiomes` is a submodule.** Don't edit it in place; changes go through the fork and a submodule pin bump.
7. **English everywhere in code, comments, and commit messages.**
8. **Markdown stays in the established documents.** User-facing behavior goes in `README.md`/`README_CN.md` (maintainers curate those); developer contracts and workflows go in the existing `docs/` files (e.g. `docs/reference-specs/`). Do not add new `.md` files for measurement reports, validation logs, benchmark summaries, or build/binary provenance — that evidence belongs in the PR description, an issue, or tests. `docs/performance/` and `native/prebuilt/BUILD_INFO.md` were removed on purpose; do not reintroduce them.

## Testing

Match the verification to what changed:

- Most changes: `./gradlew :common:test` plus the tests of the versions you touched.
- Paper changes: `./gradlew :paper:check`.
- Map sync, prediction, or native changes: CI runs the full version matrix on the PR; test what you can locally.

Native integration tests skip themselves when the platform library can't initialize — skipped native tests on an unusual platform are expected, not a failure. Behavior changes should come with focused tests through the existing public seams.

## Pull requests

- Reference the issue in the PR description (for example `Fixes #123`).
- PR titles follow [Conventional Commits](https://www.conventionalcommits.org/) style, like the existing history, e.g. `fix(core): …`.
- CI builds and tests the whole version matrix on every PR and must pass.
- PRs are squash-merged, so the commit history inside a PR branch doesn't need to be polished.

## Getting help

Open an issue for anything that needs discussion, or reach the maintainers through the channels listed in the [README](README.md#contact) (email and QQ group; English and Chinese both fine).

## License

Conflux Map is [GPL-3.0](LICENSE). By contributing, you agree that your contributions are licensed under GPL-3.0 as well. Any vendored or referenced third-party code must be recorded in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
