package com.lanqi.weiassist.shell;

import android.hardware.HardwareBuffer;

/** Live frames of a virtual display, from the shell helper to the app (see IShellService.watch). */
oneway interface IFrameListener {
    /**
     * A new frame: the display's own GPU buffer (no copy). The helper doesn't reuse it until the app
     * calls frameDone(displayId, seq) for it (or a later frame).
     */
    void onFrame(int displayId, in HardwareBuffer buffer, int seq);
}
