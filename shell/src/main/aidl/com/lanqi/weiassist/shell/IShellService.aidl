package com.lanqi.weiassist.shell;

import android.os.Bundle;
import com.lanqi.weiassist.shell.IFrameListener;

/**
 * The shell helper (ShellServer, running as the shell user) as the app sees it.
 * Only the app's own uid may call it.
 */
interface IShellService {
    /** The helper's uid (2000 = shell, 0 = root) and pid. */
    int getUid();
    int getPid();
    /** The app build it was started from (the APK's last update time); a mismatch means restart it. */
    long getVersion();
    /** Run a command with sh -c. Returns { "code": exit code (-1 = timed out), "out": stdout and stderr }. */
    Bundle exec(String command, long timeoutMs);
    /** End the helper. */
    oneway void exit();

    // ---- virtual displays (one per phone session) ----------------------------------------------------

    /** Create a virtual display that other apps can be launched on; returns its display id. */
    int createDisplay(String name, int width, int height, int densityDpi);
    /** Remove it; the apps on it are closed. */
    void releaseDisplay(int displayId);
    /** Launch [component] ("package/.Activity") on the display in a task of its own. Returns am's output. */
    String startActivity(int displayId, String component);
    /** A JPEG of the display's latest frame, at most [maxWidth] wide; empty if there is no frame yet. */
    byte[] screenshot(int displayId, int maxWidth, int quality);
    /** Touch at a point; [durationMs] >= 500 is a long press. Returns whether input was injected. */
    boolean tap(int displayId, float x, float y, long durationMs);
    /** A straight swipe like a finger, over [durationMs]. */
    boolean swipe(int displayId, float x1, float y1, float x2, float y2, long durationMs);
    /** A key (KeyEvent.KEYCODE_*) pressed and released on the display. */
    boolean key(int displayId, int keyCode);
    /** Which apps are on which display: the display and activity lines of `dumpsys activity activities`. */
    String activities();

    // ---- live previews --------------------------------------------------------------------------------

    /**
     * Send [listener] the display's frames, at most [maxFps] a second and only when the picture changed;
     * the latest frame at once. Calling again changes the rate. Ends with unwatch, or when the app dies.
     */
    void watch(int displayId, IFrameListener listener, int maxFps);
    void unwatch(int displayId, IFrameListener listener);
    /** The app is done with frames up to [seq] (it shows a newer one): their buffers may be reused. */
    oneway void frameDone(int displayId, IFrameListener listener, int seq);
    /**
     * Whether the display renders. Off: its output is disconnected, so its apps stop drawing (an idle
     * session nobody watches); the last frame stays for screenshots and previews.
     */
    void setDisplayActive(int displayId, boolean active);
}
