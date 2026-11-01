# Native policy API probe

Run the mandatory check from the pack source root with the pack's JDK (currently
Java 25):

```text
python tools/world-policy/verify_native.py --jar path/to/lanmc-world-menu-1.0.0.jar
```

`--report .cache/world-policy/native-verification.json` saves a compact proof.
Official Minecraft and NeoForge dependencies are resolved through `build.py` and
checked against their published hashes. A missing dependency or failed check
fails the command; there is no skipped-test path. Temporary compilation/runtime
files and the diagnostic log stay under the ignored `.cache/world-policy` tree.

The probe runs the built component JAR rather than recompiling its production
source. It uses the actual Minecraft `Button`, translated components,
`PauseScreen`, native screen widget removal/focus/hit testing, and NeoForge
`ScreenEvent.Init.Post`. `Unsafe.allocateInstance` only bypasses screen
construction that requires a running Minecraft singleton; the three native
widget lists are initialized explicitly. The event's add/remove callbacks call
the real protected `Screen` methods. No Minecraft or NeoForge classpath class is
replaced by a test copy.

Before constructing the positive test buttons, the probe reads the actual
native `PauseScreen.WORLD_OPTIONS` component and requires its translation key to
remain `options.worldOptions.button`. ASM reads the loaded `PauseScreen.class`
resource and verifies the unique field read in `createPauseMenu`, its exact
`openScreenButton(Component, Supplier): Button` call and the captured supplier's
native `WorldOptionsScreen(Screen, Level)` constructor producer. The positive
buttons use that native caption. A removed/renamed native entry, changed key,
builder descriptor or screen producer therefore fails before a synthetic test
could conceal an API change. Six in-memory negative mutations exercise these
rejections; the mutated classes are never loaded or executed.

The failed-LAN fallback check injects a constructor-free native `Minecraft` and
`Gui` into the real singleton field, then calls the real native `Gui.screen()`
getter. An explicitly bounded `RecordingWorldOptions` subclass overrides only
`onClose` to count navigation and change the fixture's current-screen reference.
This avoids invoking `Gui.setScreen`, which would initialize the parent and
require a running client. The singleton is restored in a `finally` block.
The actual component handler receives real `Init.Post` events: a still-visible
world-options instance closes once, whereas successful publication with a null
screen, a replacement screen, a newer world-options instance, and repeated stale
events remain untouched. Native settings widgets and their callbacks are not
applied by this check.

Separately, ASM pins the actual `WorldOptionsScreen.onClose` body to the sole
`Gui.setScreen(lastScreen)` navigation and verifies its inherited native
`Screen.removed()` body is empty. Negative mutations adding a settings mutator
to either callback must fail. Thus the recording navigation boundary is explicit;
the probe does not claim it executed native `Gui.setScreen` or applied changes.

Assertions cover exact translated-key removal, duplicate matching buttons,
child/render/narration lists, focused-widget cleanup, absence from native click
dispatch, retained Options/LAN/Reports callbacks, unrelated focus, literal and
near-key captions, non-button labels, non-pause screens, repeated events and
fresh widget-list rebuilding. ASM reads the packaged client-only mod/subscriber
annotations and the native handler descriptor. The packaged TOML is checked for
the matching required client dependencies.

After offline registry bootstrap, the actual client and server
`PackMetadataSection` codecs parse the JAR's `pack.mcmeta`, verify that it covers
the current native format, and reject malformed metadata.

This proves the native API interaction and the packaged metadata. It does not
start a client/server main, renderer or world; it does not prove event-bus mod
discovery, a full screen resize, client startup, gameplay or a visual result.
