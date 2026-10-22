# Open a QuickBar through an Android shortcut

QuickBars provides an **Open a QuickBar** shortcut picker for launchers and apps
that support Android shortcuts. Choose an enabled QuickBar when creating the
shortcut. The shortcut uses its saved ID, so it continues to work after a rename.
Create a new shortcut if the QuickBar is deleted and recreated.

For example, in Button Mapper, choose a button and press type, select **Shortcuts**,
then **Open a QuickBar**, and choose a bar. Button Mapper handles the remote keys;
QuickBars' accessibility service can stay disabled. Other shortcut-capable apps
can use the same picker.

QuickBars still needs Android's **Display over other apps** permission. Missing
permission or an unavailable QuickBar produces an on-screen message. Back closes
the sidebar. Apps that remap D-pad, OK, or Back must still allow navigation in it.

The shortcut can start QuickBars when its process is not running. With
accessibility disabled, an on-demand foreground service hosts the overlay and
stops when the bar closes. It does not listen for global key events or start at
boot. The optional persistent Home Assistant connection remains a separate setting.
When accessibility is enabled, shortcuts use the existing accessibility host.
In shortcut-only mode, camera PiPs and notification overlays share the sidebar's
lifetime and are removed when it closes.

## Activity intent

Apps that can start an Android activity can also open a bar by ID or exact name.
This is an activity intent, not a broadcast.

| Field | Value |
| --- | --- |
| Action | `dev.trooped.tvquickbars.OPEN_QUICKBAR` |
| Component | `dev.trooped.tvquickbars/dev.trooped.tvquickbars.ui.OpenQuickBarActivity` |
| ID selector | String extra `QUICKBAR_ID` |
| Name selector | String extra `QUICKBAR_NAME` |

Supply exactly one selector. Names are case-sensitive, and duplicate names are
rejected; use an ID to distinguish them. IDs are available in `quick_bars[].id`
in an exported backup. Disabled bars cannot be opened. Android's activity-launch
restrictions still apply to callers.

For debug builds, the package is `dev.trooped.tvquickbars.debug`; the activity
class and action remain the same.

## Check with ADB

These examples use the debug package and a bar named `Living room`. Grant the
debug app overlay permission first, and leave QuickBars accessibility disabled.

```sh
adb shell am force-stop dev.trooped.tvquickbars.debug
adb shell 'am start -W \
  -n dev.trooped.tvquickbars.debug/dev.trooped.tvquickbars.ui.OpenQuickBarActivity \
  -a dev.trooped.tvquickbars.OPEN_QUICKBAR \
  --es QUICKBAR_NAME "Living room"'

# Alternatively, use an ID instead of a name.
adb shell am start -W \
  -n dev.trooped.tvquickbars.debug/dev.trooped.tvquickbars.ui.OpenQuickBarActivity \
  -a dev.trooped.tvquickbars.OPEN_QUICKBAR \
  --es QUICKBAR_ID REPLACE_WITH_BAR_ID

# Close the overlay and check that its on-demand service stops after the animation.
adb shell input keyevent 4
adb shell dumpsys activity services dev.trooped.tvquickbars.debug
```

The process may remain cached after closing; that does not mean a service is
running. The separately enabled persistent HA connection may still be running.

## Development checks

Use the debug setup in [CONTRIBUTING.md](../CONTRIBUTING.md), then run:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The runtime tests cover overlay composition without accessibility, repeated opens,
bar switching, local Back/navigation handling, service cleanup, and the existing
accessibility key path. They do not replace the physical Android TV checks in the
contribution guidelines.
