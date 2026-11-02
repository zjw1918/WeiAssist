package com.lanqi.weiassist.shell;

import android.os.Bundle;

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
}
