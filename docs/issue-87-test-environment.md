# Issue #87 test environment

This profile reproduces and records the Chat Heads compatibility failure without enabling chat
logging in normal Conflux Map runs.

## Locked environment

| Component | Version |
|---|---|
| Minecraft | 1.21.11 |
| Conflux Map baseline | 0.1.4-beta.9 (`4270e25a`) |
| Fabric Loader | 0.17.3 |
| Fabric API | 0.141.5+1.21.11 |
| Chat Heads | 1.2.8 (Modrinth version `uQ4oSnyu`) |
| Java toolchain | 21 |

Chat Heads is pinned by its immutable Modrinth project and version IDs. Xaero's Minimap is not
required: Conflux Map sends its own Conflux-formatted message followed by its Xaero-compatible
message, which provides the control case described in the issue.

## Start

From the repository root on Windows:

```powershell
.\gradlew.bat :1.21.11:runClient -Pissue87
```

The profile uses `versions/1.21.11/build/issue-87-client` as an isolated game directory, installs
Chat Heads 1.2.8 on the development runtime classpath, and enables
`-Dconfluxmap.issue87.debug=true`. The first launch downloads the locked dependencies.

## Reproduce

1. Create or open a single-player world. Keep Chat Heads at its default `Before Name` render
   position.
2. In Conflux Map, create a waypoint and choose **Share Coordinates in Chat**.
3. Confirm the preview. Conflux Map sends two messages in order: Conflux, then Xaero-compatible.
4. Compare the first message with the second. The failure is present when the first line contains
   a literal `%s head`/head placeholder while the second retains the rendered player head.
5. Exit the client so `latest.log` is complete.

The diagnostic log is written to:

```text
versions/1.21.11/build/issue-87-client/logs/latest.log
```

Filter the evidence with:

```powershell
Select-String -Path versions/1.21.11/build/issue-87-client/logs/latest.log -Pattern '\[issue-87'
```

The automated characterization can be run without opening Minecraft:

```powershell
.\gradlew.bat :1.21.11:test -Pissue87 -x :common:test `
  --tests cn.net.rms.confluxmap.mc.chat.WaypointChatDiagnosticsTest `
  --tests cn.net.rms.confluxmap.mc.chat.WaypointChatMessageRewriterTest
```

It constructs the same 1.21.11 player-sprite component Chat Heads inserts and verifies that both
the input and compact Conflux output retain `ObjectTextContent`/`PlayerTextObjectContents`.

The first diagnostic line records every relevant mod/runtime version. Each outbound share and
each recognized rewrite has an event ID. Rewrite records include input/output visible text and
the complete bounded component tree (component/content classes, sibling paths, styles, and
subtree text). Private click payload bytes are redacted. Diagnostics are opt-in because the raw
messages can contain player names and coordinates.

## Cause report

Chat Heads 1.2.8 decorates a player message in `ChatListener` before it reaches `ChatHud`. In its
default `Before Name` mode it inserts Minecraft 1.21.11's `ObjectTextContent`/`PlayerSprite` into
the message component tree.

Conflux Map then intercepts `ChatHud.addMessage`. `WaypointChatMessageRewriter.rewrite` calls
`original.getString()` to parse the message. For a Conflux-formatted share it uses that flattened
string to create a new literal component for the compact display. Minecraft's textual fallback
for the player sprite is the observed head placeholder, so this step both materializes the
placeholder and discards the original `PlayerSprite` object.

The Xaero-compatible branch behaves differently: it appends `original.shallowCopy()` instead of
rebuilding the message from `getString()`. That preserves Chat Heads' object component, explaining
why the immediately following Xaero-formatted message renders normally. This is a component-tree
preservation defect, not an unexpanded `%s` in Conflux Map's language files or outgoing protocol.

The compatibility fix reconstructs vanilla's `chat.type.text` component with a cloned argument
array. It keeps the first sender argument, including Chat Heads' player sprite and name styles,
and replaces only the second message argument with Conflux Map's compact waypoint label.
