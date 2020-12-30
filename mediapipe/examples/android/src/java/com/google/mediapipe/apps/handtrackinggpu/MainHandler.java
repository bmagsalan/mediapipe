package com.google.mediapipe.apps.handtrackinggpu;

import android.app.Activity;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Custom message handler for main UI thread.
 * <p>
 * Receives messages from the renderer thread with UI-related updates, like the camera
 * parameters (which we show in a text message on screen).
 */
public class MainHandler extends Handler {
    private static String TAG = MainHandler.class.getSimpleName();
    private static final int MSG_SEND_CAMERA_PARAMS0 = 0;
    private static final int MSG_SEND_CAMERA_PARAMS1 = 1;
    private static final int MSG_SEND_RECT_SIZE = 2;
    private static final int MSG_SEND_ZOOM_AREA = 3;
    private static final int MSG_SEND_ROTATE_DEG = 4;

    private WeakReference<Activity> mWeakActivity;

    public MainHandler(Activity activity) {
        mWeakActivity = new WeakReference<Activity>(activity);
    }

    /**
     * Sends the updated camera parameters to the main thread.
     * <p>
     * Call from render thread.
     */
    public void sendCameraParams(int width, int height, float fps) {
        // The right way to do this is to bundle them up into an object.  The lazy
        // way is to send two messages.
        sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS0, width, height));
        sendMessage(obtainMessage(MSG_SEND_CAMERA_PARAMS1, (int) (fps * 1000), 0));
    }

    /**
     * Sends the updated rect size to the main thread.
     * <p>
     * Call from render thread.
     */
    public void sendRectSize(int width, int height) {
        sendMessage(obtainMessage(MSG_SEND_RECT_SIZE, width, height));
    }

    /**
     * Sends the updated zoom area to the main thread.
     * <p>
     * Call from render thread.
     */
    public void sendZoomArea(int width, int height) {
        sendMessage(obtainMessage(MSG_SEND_ZOOM_AREA, width, height));
    }

    /**
     * Sends the updated zoom area to the main thread.
     * <p>
     * Call from render thread.
     */
    public void sendRotateDeg(int rot) {
        sendMessage(obtainMessage(MSG_SEND_ROTATE_DEG, rot, 0));
    }

    @Override
    public void handleMessage(Message msg) {
        Activity activity = mWeakActivity.get();
        if (activity == null) {
            Log.d(TAG, "Got message for dead activity");
            return;
        }


    }
}

