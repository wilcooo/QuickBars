# Open QuickBars from Button Mapper

This local patch adds Android shortcuts and an exported **activity** intent for
opening a saved QuickBar without enabling QuickBars' accessibility service.
It is based on upstream commit `1478854aa5fa89831e9e7a1b4f8af4e61f031c74`
(version 1.3.3, versionCode 25). No upstream changes or pull request were submitted.

## Update 2: Mi Box crash fix and diagnostics

The updated debug APK identifies itself as **1.3.3-external.2**. Install it over
our previous debug APK; do **not** uninstall that copy. It retains the same
package, signing identity, saved configuration, and shortcut activity/action,
so the existing Button Mapper shortcut should continue to work.

The original external overlay could crash asynchronously during its first view
traversal with `IllegalStateException: ViewTreeLifecycleOwner not found`.
Compose looks for the window lifecycle on the outermost view, while the first
patch installed it only on the Compose child inside the new Back-key wrapper.
The fix installs lifecycle/saved-state owners on both views before attachment.
A Robolectric Android runtime regression test reproduced that exact exception
before the fix and renders the overlay after it.

To test directly on the TV:

1. Open **QuickBars** in the custom app and select **Show QuickBar** beside an
   enabled saved bar. Accessibility can stay disabled throughout.
2. If asked, grant **Display over other apps** to this custom app. Opening a bar
   does not require having opened it via accessibility first.
3. Press **Back** to close it, then try the existing Button Mapper assignment,
   including from the launcher or another app.
4. If it still fails, reopen the custom app and select **Diagnostics** at the
   bottom of the QuickBars list. Select **QR code**, scan it with your phone,
   and send the resulting text. A photo of the diagnostic screen also works.
   Use **Copy report** or **Save report** for the full stack trace if convenient;
   Save requires an Android document picker, which some TVs do not provide.

Diagnostics retain the last uncaught exception across app restarts and a bounded
list of startup stages. They include device/Android version, build version,
overlay permission and service status. The recorder is active only in debug
builds and stores the report locally. It never reads HA credentials or entity
states, and redacts URLs, bearer tokens and long opaque values from exceptions.
Nothing is automatically uploaded; review the report before sharing it.

The launch activity now waits for the overlay service to acknowledge startup
before finishing. Invalid selections, missing permission, and synchronous
startup failures show an on-screen dialog with access to Diagnostics instead
of relying only on a brief toast. An absent acknowledgement times out after
10 seconds. The foreground notification uses a dedicated monochrome icon.

## Findings in upstream

- `app/src/main/AndroidManifest.xml` exposes the launcher and main activities, but
  no receiver, shortcut provider or activity action for opening a particular bar.
  The boot receiver is private. `QuickBarService` is private and protected with
  `BIND_ACCESSIBILITY_SERVICE`; its start commands only capture/cancel/reload keys.
- `services/QuickBarService.kt` extends `AccessibilityService`. Its
  `onServiceConnected()` initializes the window manager, Compose lifecycle, bar
  configuration, camera and notification controllers. Simply adding an intent
  receiver would leave those prerequisites uninitialized when accessibility is off.
- Its `createWindowParams()` uses `TYPE_APPLICATION_OVERLAY`, not
  `TYPE_ACCESSIBILITY_OVERLAY`. The necessary window permission is
  `SYSTEM_ALERT_WINDOW` ("display over other apps"). Accessibility is needed for
  the current global key interception, not for this window type.
- There is an existing Home Assistant event, `quickbars.open`, with an `alias`.
  `ha/ws/handlers/QuickBarsOpenHandler.kt` routes it to a listener, and
  `background/BackgroundHaConnectionManager.kt` forwards it to the running
  `QuickBarService.serviceInstance`. This is an alternative only if the existing
  service/socket arrangement is acceptable; it does not supply the requested
  accessibility-free cold-start solution.
- No existing public intent accepting a bar ID/name was found. An ADB broadcast
  to an invented `OPEN_QUICKBAR` action cannot make the original APK open a bar.

Sources: [repository](https://github.com/Trooped/QuickBars),
[original manifest](https://github.com/Trooped/QuickBars/blob/1478854aa5fa89831e9e7a1b4f8af4e61f031c74/app/src/main/AndroidManifest.xml),
[original service](https://github.com/Trooped/QuickBars/blob/1478854aa5fa89831e9e7a1b4f8af4e61f031c74/app/src/main/java/dev/trooped/tvquickbars/services/QuickBarService.kt).

## Patch design

The existing runtime is moved, with limited changes, into `QuickBarController`.
`QuickBarService` remains the same Android accessibility component and forwards
its existing lifecycle, editor commands and global key events to that controller.
This preserves the existing trigger keys, failsafe, HA actions, camera and
notification implementation rather than creating a second sidebar renderer.
There is no change to the saved configuration format.
Editor reload commands also stop immediately if the accessibility service is
unbound, so saving a bar cannot leave an idle key-listener service behind.

`OpenQuickBarActivity` is a short-lived translucent launch activity. It validates
one ID/name selector and the overlay permission. If accessibility is already
connected it reuses that host. Otherwise it starts the private
`QuickBarOverlayService`, which hosts the same runtime until the bar closes.
The activity then finishes, returning to the previous app. The service is
`START_NOT_STICKY`, is never started at boot, and does not intercept global keys.
Back and auto-hide activity are handled by the focused overlay's own window.

An ordinary broadcast listener plus background service is not a reliable cold
start on current Android. Foreground services have background-start restrictions,
and Android 15 tightens the overlay-permission exemption to require an already
visible overlay. The launch activity provides a user-triggered entry point;
the on-demand service keeps the overlay alive after it finishes. Its foreground
notification and `specialUse` declaration apply only to that displayed session.
Closing the bar removes the notification, destroys the runtime and stops the
service. The existing optional persistent HA connection remains a separate setting.

Repeated OPEN requests for the same displayed ID keep it open and reset its
auto-hide timer. Requests for another bar switch to that bar. Requests received
during closing are serialized so stale view-removal callbacks cannot discard the
new bar. Existing accessibility triggers retain their toggle behavior.

This is a bounded extraction from a large service, not a new rendering system.
The diff appears large because the existing runtime moves to a new file. A future
upstream contribution would still need maintainer discussion and real TV testing,
including review of the new `specialUse` foreground-service declaration.

Android references:
[application overlays](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY),
[foreground service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[special-use services](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

## Install and configure

The supplied debug build has package **`dev.trooped.tvquickbars.debug`**. It can
coexist with the Play Store app; it cannot overwrite the original package or
access its private configuration. Both may have the same launcher label/icon.

1. In the existing app, export a backup including entities and QuickBars.
2. Install the debug APK, for example:
   ```sh
   adb install -r QuickBars-external-debug.apk
   ```
3. Open the debug app, connect it to Home Assistant and restore that backup.
   The app's backup contains bars/entities/trigger keys, not HA credentials, so
   configure the URL/token separately. Do not enable QuickBars accessibility.
4. Allow the **debug app** to display over other apps in Android settings. If the
   TV does not expose that setting, the ADB equivalent is:
   ```sh
   adb shell appops set dev.trooped.tvquickbars.debug SYSTEM_ALERT_WINDOW allow
   ```
5. Leave **Persistent Background Connection to Home Assistant** disabled if you
   want no idle QuickBars service. The external launcher does not enable it.
6. In **Button Mapper by flar2**, select/add the remote button, enable its
   customization, and select single press, double press or long press.
7. In the action selector choose **Shortcuts**, then **Open a QuickBar**. That
   entry is provided by this patch. Choose the saved bar in the QuickBars picker.
   Repeat for other buttons/press types and other bars.
8. Leave QuickBars accessibility disabled and Button Mapper accessibility enabled.
   Press the mapped button over another app. Use Back to close the sidebar.
   D-pad/OK/Back must reach the focused window; remapping those navigation keys
   to unrelated actions in Button Mapper can prevent normal sidebar navigation.

Button Mapper's action-selector labels may vary by version. Its published app
listing confirms shortcut launching; this integration uses the standard
`ACTION_CREATE_SHORTCUT` result protocol. The shortcut contains the stable bar
ID, so renaming a bar does not break it. Deleting/recreating a bar requires a new
shortcut. Button Mapper itself was not run in this environment.

The debug build uses the upstream-documented placeholder RevenueCat key. It is
for testing the feature; store purchases/subscription restoration are not
validated with it. The patch does not bypass entitlements or change limits.

## Intent contract

| Field | Value |
| --- | --- |
| Mechanism | Start activity (not broadcast or start-service) |
| Action | `dev.trooped.tvquickbars.OPEN_QUICKBAR` |
| Debug package | `dev.trooped.tvquickbars.debug` |
| Activity class | `dev.trooped.tvquickbars.ui.OpenQuickBarActivity` |
| ID selector | String extra `QUICKBAR_ID` |
| Name selector | String extra `QUICKBAR_NAME` |

Supply **exactly one** selector. Names are exact and case-sensitive. Missing,
blank, nonexistent, disabled or ambiguous selectors are rejected with an on-screen message.
Duplicate names are rejected even if only one of the duplicates is enabled;
use an ID instead. Names and HA trigger aliases are different fields.
Any app allowed by Android to launch the activity can request a configured bar;
there is no new network endpoint, arbitrary HA service-call API, or credential API.

If Button Mapper's custom-intent editor supports **activity** launches, the same
contract can be entered there. Its **Broadcast intent** action is unsuitable for
this patch. The shortcut route avoids version-specific custom-intent editor UI.

For an upstream release build, replace the debug package with
`dev.trooped.tvquickbars`; the action and full activity class stay the same.

## ADB tests

These examples use Linux/macOS shell quoting so names containing spaces reach
the Android shell intact. Replace `Living room` with an existing enabled bar.

```sh
# Verify QuickBars accessibility is absent; do not overwrite this setting,
# because Button Mapper and other accessibility services may be listed.
adb shell settings get secure enabled_accessibility_services

# Kill the debug app first, retaining its saved configuration.
adb shell am force-stop dev.trooped.tvquickbars.debug

# Open by exact name, cold-starting the process.
adb shell 'am start -W \
  -n dev.trooped.tvquickbars.debug/dev.trooped.tvquickbars.ui.OpenQuickBarActivity \
  -a dev.trooped.tvquickbars.OPEN_QUICKBAR \
  --es QUICKBAR_NAME "Living room"'

# Or open by ID (obtain it from quick_bars[].id in an exported backup).
adb shell am start -W \
  -n dev.trooped.tvquickbars.debug/dev.trooped.tvquickbars.ui.OpenQuickBarActivity \
  -a dev.trooped.tvquickbars.OPEN_QUICKBAR \
  --es QUICKBAR_ID REPLACE_WITH_BAR_ID

# Close using Back, then inspect the running-service and window state.
adb shell input keyevent 4
sleep 1
adb shell dumpsys activity services dev.trooped.tvquickbars.debug
adb shell dumpsys window windows

# Inspect permission and relevant runtime errors if needed.
adb shell appops get dev.trooped.tvquickbars.debug SYSTEM_ALERT_WINDOW
adb logcat -s QuickBarDiagnostics QuickBarService AndroidRuntime
```

After the closing animation (about 370 ms), `QuickBarOverlayService` should no
longer be running and its window/notification should be gone. Another configured
service, such as the optional persistent HA connection, may still be present.
The process may remain cached; that does not mean a service is running.

## Build and verification

The source ZIP contains the complete patched project and a standalone patch.
To apply just the patch to a separate checkout:

```sh
git clone https://github.com/Trooped/QuickBars.git
cd QuickBars
git checkout 1478854aa5fa89831e9e7a1b4f8af4e61f031c74
git apply /path/to/QuickBars-external.patch
```

Requires JDK 17, Android SDK platform 36, Build Tools 35.0.0, and network access to
Google Maven/Maven Central/Gradle distributions. From the project directory:

```sh
bash gradlew :app:assembleDebug :app:testDebugUnitTest \
  '-PREVENUECAT_API_KEY="dummy_key"'
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.
The selector tests cover ID/name selection, missing/conflicting/blank selectors,
disabled bars, duplicate names, case sensitivity, and renaming with a stable ID.
Runtime regression tests additionally exercise overlay attachment/composition without
accessibility, repeated opens, Back cleanup, cold service startup/acknowledgement,
permission rejection, and diagnostic redaction.
See `VALIDATION.txt` in the delivery bundle for the actual results of this run.

The signed debug APK has a local debug signing identity, not the upstream key.
Keep the Play Store app installed while testing. Rebuilding with a different
local signing key may require backing up and uninstalling only the debug copy.

## Real-TV checks still required

There is no connected Android TV here. Before treating this as production-ready:

- Test Button Mapper's shortcut picker, trigger execution and repeated presses.
- Test cold launch after force-stop, Back with a focused card and an empty bar,
  navigation, auto-hide reset, per-domain auto-close, and rapid bar switching.
- Confirm the on-demand service/notification disappear after closing.
- Check Home Assistant entity updates and actions over the real network.
- Test while video is playing: the brief translucent activity may cause some
  players to pause. The overlay service itself does not request audio focus.
- Test Android TV versions/OEMs in use, especially Android 14/15 start rules.
- Enable QuickBars accessibility separately and regression-test trigger-key
  capture, single/double/long press, failsafe, notifications and camera PiP.
  Enabling it while an external bar is open closes the external session before
  the accessibility runtime takes ownership. In external mode, auxiliary PiPs
  are session-bound and are also cleaned up when the bar closes.

These are pending device checks, not claims of tests already performed.
