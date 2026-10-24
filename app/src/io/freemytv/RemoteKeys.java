package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.InputDevice;
import android.view.KeyEvent;

/**
 * The remote's app buttons on TCL TVs. The stock home screen opens the apps for Netflix, YouTube and Prime (and TCL
 * Channel); with it off, the keys still arrive, so we open the app the person picked (Settings > Remote Buttons).
 * Disney and Globoplay are opened by the system itself, so they are not listed. The YouTube button (BUTTON_3) never
 * reaches an app's window; only the accessibility service's key filter (RemoteKeyService) sees it.
 * On Vestel TVs and Formovie (Xiaomi) projectors only the Netflix button is ours: the system opens Netflix itself (a
 * global key, after the filter).
 * On KTC TVs (NextStar) only the Tabii button is ours, a global key too: the TV's hot-key receiver opens Tabii with it.
 * On Sony TVs, the app buttons (and the Google Play button of older remotes) are global keys for Sony's own receiver;
 * the filter sees them first, the same way.
 * On OnePlus TVs the three app buttons are global keys too, for the TV's hot-key dispatcher; Menu and the mic button
 * are plain keys. A button left on "Nothing" works as always.
 * Besides an app, a button can show the cameras (camera_hdmi) or start YouTube's voice search.
 */
final class RemoteKeys {
    private RemoteKeys() {}

    /** Some TCL remotes (BT_RC833) send TCL's own YouTube key instead of BUTTON_3: the same button. */
    private static final int KEY_YOUTUBE_TCL = 4065;

    /**
     * The key codes are the brand's own: on other brands they can be other buttons, so nothing here runs there.
     * Vestel builds TVs sold under other brands (F&U...), so BRAND varies: check the maker too.
     */
    static final boolean TCL = is("TCL"), VESTEL = is("Vestel"), KTC = is("KTC"), SONY = is("Sony"), FORMOVIE = is("Formovie"),
            ONEPLUS = is("OnePlus");
    /** Brands whose Netflix button is STEM_1, a global key (MediaTek): only that button is ours. */
    static final boolean STEM_NETFLIX = VESTEL || FORMOVIE;

    static boolean supported() {
        return TCL || STEM_NETFLIX || KTC || SONY || ONEPLUS;
    }

    /** A button's choice that opens YouTube and starts its voice search (YouTube listens on the mic key). */
    static final String VOICE = "io.freemytv.voice";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    /** The app in front, from the service's window events: in YouTube, the mic key is left to YouTube. */
    static volatile String foreground = "";

    private static boolean is(String maker) {
        return maker.equalsIgnoreCase(Build.MANUFACTURER) || maker.equalsIgnoreCase(Build.BRAND);
    }

    /** Opens the app for this key on its first press. True if the key is ours and in use (it is then consumed). */
    static boolean onKey(Context c, KeyEvent e) {
        if (!supported()) return false;
        // BUTTON_3 is also a game controller's button: keys from a gamepad or a joystick are left alone.
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;
        int keyCode = e.getKeyCode() == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : e.getKeyCode();
        // Sony sends BUTTON_9 for five app buttons (BUTTON_4 for Netflix, BUTTON_8 for Google Play): only the scan code
        // tells them apart.
        if (SONY && (keyCode == KeyEvent.KEYCODE_BUTTON_4 || keyCode == KeyEvent.KEYCODE_BUTTON_8
                || keyCode == KeyEvent.KEYCODE_BUTTON_9)) {
            keyCode = Prefs.SONY_KEY + e.getScanCode();
        }
        boolean ours = false;
        for (int key : Prefs.KEYS) ours |= key == keyCode;
        if (!ours) return false;
        String pkg = Prefs.keyApp(keyCode);
        if (pkg.isEmpty()) return false;   // "Nothing": the key goes on as if we weren't here
        if (pkg.equals(VOICE) && keyCode == KeyEvent.KEYCODE_SEARCH && YOUTUBE.equals(foreground)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            if (pkg.equals(VOICE)) {
                voiceSearch(c);
                return true;
            }
            if (pkg.equals(HomeApps.CAMERA)) {
                Intent cameras = HomeApps.cameras(c, false);
                AppLog.w("remote key " + e.getKeyCode() + " -> cameras" + (cameras == null ? " (no camera_hdmi)" : ""));
                if (cameras != null) c.startActivity(cameras.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
            Intent intent = launchIntent(c.getPackageManager(), pkg);
            AppLog.w("remote key " + e.getKeyCode() + " -> " + pkg + (intent == null ? " (not installed)" : ""));
            if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return true;
    }

    /**
     * Opens YouTube (unless it's in front already), then presses the mic key through the TV's own ADB once YouTube is
     * in front: it opens YouTube's voice search. That key reaches YouTube (see onKey).
     */
    private static void voiceSearch(Context c) {
        final Context app = c.getApplicationContext();
        if (!YOUTUBE.equals(foreground)) {
            Intent intent = launchIntent(app.getPackageManager(), YOUTUBE);
            if (intent == null) return;
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        new Thread(() -> {
            try {
                for (int i = 0; i < 40 && !YOUTUBE.equals(foreground); i++) Thread.sleep(250);
                if (!YOUTUBE.equals(foreground)) return;   // the key would come back here, not to YouTube
                Thread.sleep(1500);   // its search screen takes a moment after the window appears
                try (Adb adb = Adb.connect(app, 3_000, null)) {
                    adb.shell("input keyevent " + KeyEvent.KEYCODE_SEARCH);
                }
            } catch (Exception e) {
                AppLog.w("voice search: " + e.getMessage());
            }
        }, "voice-search").start();
    }

    /** The app's TV launcher, or its plain one (kept here, not in HomeApps, so the key process stays small). */
    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
