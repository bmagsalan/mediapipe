package com.google.mediapipe.apps.handtrackinggpu;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.google.mediapipe.framework.AndroidAssetUtil;

/**
 * Direct the Camera preview to a GLES texture and manipulate it.
 * <p>
 * We manage the Camera and GLES rendering from a dedicated thread.  We don't animate anything,
 * so we don't need a Choreographer heartbeat -- just redraw when we get a new frame from the
 * camera or the user has caused a change in size or position.
 * <p>
 * The Camera needs to follow the activity pause/resume cycle so we don't keep it locked
 * while we're in the background.  Also, for power reasons, we don't want to keep getting
 * frames when the screen is off.  As noted in
 * http://source.android.com/devices/graphics/architecture.html#activity
 * the Surface lifecycle isn't quite the same as the activity's.  We follow approach #1.
 * <p>
 * The tricky part about the lifecycle is that our SurfaceView's Surface can outlive the
 * Activity, and we can get surface callbacks while paused, so we need to keep track of it
 * in a static variable and be prepared for calls at odd times.
 * <p>
 * The zoom, size, and rotate values are determined by the values stored in the "seek bars"
 * (sliders).  When the device is rotated, the Activity is paused and resumed, but the
 * controls retain their value, which is kind of nice.  The position, set by touch, is lost
 * on rotation.
 * <p>
 * The UI updates go through a multi-stage process:
 * <ol>
 * <li> The user updates a slider.
 * <li> The new value is passed as a percent to the render thread.
 * <li> The render thread converts the percent to something concrete (e.g. size in pixels).
 *      The rect geometry is updated.
 * <li> (For most things) The values computed by the render thread are sent back to the main
 *      UI thread.
 * <li> (For most things) The UI thread updates some text views.
 * </ol>
 */
public class MergedActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = CameraActivity.class.getSimpleName();

    private static final int DEFAULT_ZOOM_PERCENT = 0;      // 0-100
    private static final int DEFAULT_SIZE_PERCENT = 50;     // 0-100
    private static final int DEFAULT_ROTATE_PERCENT = 0;    // 0-100

    // Requested values; actual may differ.
    private static final int REQ_CAMERA_WIDTH = 1280;
    private static final int REQ_CAMERA_HEIGHT = 720;
    private static final int REQ_CAMERA_FPS = 30;

    // The holder for our SurfaceView.  The Surface can outlive the Activity (e.g. when
    // the screen is turned off and back on with the power button).
    //
    // This becomes non-null after the surfaceCreated() callback is called, and gets set
    // to null when surfaceDestroyed() is called.
    private static SurfaceHolder sSurfaceHolder;

    // Thread that handles rendering and controls the camera.  Started in onResume(),
    // stopped in onPause().
    private CameraActivity.RenderThread mRenderThread;

    // Receives messages from renderer thread.
    private CameraActivity.MainHandler mHandler;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture_from_camera);

        mHandler = new CameraActivity.MainHandler(this);

        SurfaceView sv = (SurfaceView) findViewById(R.id.cameraOnTexture_surfaceView);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume BEGIN");
        super.onResume();


        mRenderThread = new CameraActivity.RenderThread(mHandler);
        mRenderThread.setName("TexFromCam Render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        CameraActivity.RenderHandler rh = mRenderThread.getHandler();
        rh.sendZoomValue(DEFAULT_ZOOM_PERCENT);
        rh.sendSizeValue(DEFAULT_SIZE_PERCENT);
        rh.sendRotateValue(DEFAULT_ROTATE_PERCENT);

        if (sSurfaceHolder != null) {
            Log.d(TAG, "Sending previous surface");
            rh.sendSurfaceAvailable(sSurfaceHolder, false);
        } else {
            Log.d(TAG, "No previous surface");
        }
        Log.d(TAG, "onResume END");
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause BEGIN");
        super.onPause();

        if (mRenderThread == null) {
            return;
        }
        CameraActivity.RenderHandler rh = mRenderThread.getHandler();
        rh.sendShutdown();
        try {
            mRenderThread.join();
        } catch (InterruptedException ie) {
            // not expected
            throw new RuntimeException("join was interrupted", ie);
        }
        mRenderThread = null;
        Log.d(TAG, "onPause END");
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated holder=" + holder + " (static=" + sSurfaceHolder + ")");
        if (sSurfaceHolder != null) {
            throw new RuntimeException("sSurfaceHolder is already set");
        }

        sSurfaceHolder = holder;

        if (mRenderThread != null) {
            // Normal case -- render thread is running, tell it about the new surface.
            CameraActivity.RenderHandler rh = mRenderThread.getHandler();
            rh.sendSurfaceAvailable(holder, true);
        } else {
            // Sometimes see this on 4.4.x N5: power off, power on, unlock, with device in
            // landscape and a lock screen that requires portrait.  The surface-created
            // message is showing up after onPause().
            //
            // Chances are good that the surface will be destroyed before the activity is
            // unpaused, but we track it anyway.  If the activity is un-paused and we start
            // the RenderThread, the SurfaceHolder will be passed in right after the thread
            // is created.
            Log.d(TAG, "render thread not running");
        }
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                " holder=" + holder);

        if (mRenderThread != null) {
            CameraActivity.RenderHandler rh = mRenderThread.getHandler();
            rh.sendSurfaceChanged(format, width, height);
        } else {
            Log.d(TAG, "Ignoring surfaceChanged");
            return;
        }
    }

    @Override   // SurfaceHolder.Callback
    public void surfaceDestroyed(SurfaceHolder holder) {
        // In theory we should tell the RenderThread that the surface has been destroyed.
        if (mRenderThread != null) {
            CameraActivity.RenderHandler rh = mRenderThread.getHandler();
            rh.sendSurfaceDestroyed();
        }
        Log.d(TAG, "surfaceDestroyed holder=" + holder);
        sSurfaceHolder = null;
    }

    @Override

    /**
     * Handles any touch events that aren't grabbed by one of the controls.
     */
    public boolean onTouchEvent(MotionEvent e) {

        return true;
    }
}