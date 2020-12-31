package com.google.mediapipe.apps.handtrackinggpu;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.google.mediapipe.apps.handtrackinggpu.gles.EglCore;
import com.google.mediapipe.apps.handtrackinggpu.gles.GlUtil;
import com.google.mediapipe.apps.handtrackinggpu.gles.ScaledDrawable2d;
import com.google.mediapipe.apps.handtrackinggpu.gles.Sprite2d;
import com.google.mediapipe.apps.handtrackinggpu.gles.Texture2dProgram;
import com.google.mediapipe.apps.handtrackinggpu.gles.WindowSurface;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Thread that handles all rendering and camera operations.
 */
public class RenderThread extends Thread implements
        SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = RenderThread.class.getSimpleName();

    private static final int DEFAULT_ZOOM_PERCENT = 0;      // 0-100
    private static final int DEFAULT_SIZE_PERCENT = 50;     // 0-100
    private static final int DEFAULT_ROTATE_PERCENT = 0;    // 0-100
    private static final int REQ_CAMERA_WIDTH = 1280;
    private static final int REQ_CAMERA_HEIGHT = 720;
    private static final int REQ_CAMERA_FPS = 30;
    // Object must be created on render thread to get correct Looper, but is used from
    // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
    // constructed object.
    private volatile RenderHandler mHandler;

    // Used to wait for the thread to start.
    private Object mStartLock = new Object();
    private boolean mReady = false;

    private MainHandler mMainHandler;

    private Camera mCamera;
    private int mCameraPreviewWidth, mCameraPreviewHeight;

    private EglCore mEglCore;
    private WindowSurface mWindowSurface;
    private int mWindowSurfaceWidth;
    private int mWindowSurfaceHeight;

    // Receives the output from the camera preview.
    private SurfaceTexture mCameraTexture;

    // Orthographic projection matrix.
    private float[] mDisplayProjectionMatrix = new float[16];

    private Texture2dProgram mTexProgram;
    private final ScaledDrawable2d mRectDrawable =
            new ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE);
    private final Sprite2d mRect = new Sprite2d(mRectDrawable);

    private int mZoomPercent = DEFAULT_ZOOM_PERCENT;
    private int mSizePercent = DEFAULT_SIZE_PERCENT;
    private int mRotatePercent = DEFAULT_ROTATE_PERCENT;
    private float mPosX, mPosY;
    private int newWidth;
    private int newHeight;
    private Object lock = new Object();
    private ByteBuffer buffer;
    private Bitmap bitmap;
    private boolean waitingUntilLoaded;


    /**
     * Constructor.  Pass in the MainHandler, which allows us to send stuff back to the
     * Activity.
     */
    public RenderThread(MainHandler handler) {
        mMainHandler = handler;
    }

    /**
     * Thread entry point.
     */
    @Override
    public void run() {
        Looper.prepare();

        // We need to create the Handler before reporting ready.
        mHandler = new RenderHandler(this);
        synchronized (mStartLock) {
            mReady = true;
            mStartLock.notify();    // signal waitUntilReady()
        }

        // Prepare EGL and open the camera before we start handling messages.
        mEglCore = new EglCore(null, 0);
        openCamera(REQ_CAMERA_WIDTH, REQ_CAMERA_HEIGHT, REQ_CAMERA_FPS);

        Looper.loop();

        Log.d(TAG, "looper quit");
        releaseCamera();
        releaseGl();
        mEglCore.release();

        synchronized (mStartLock) {
            mReady = false;
        }
    }

    /**
     * Waits until the render thread is ready to receive messages.
     * <p>
     * Call from the UI thread.
     */
    public void waitUntilReady() {
        synchronized (mStartLock) {
            while (!mReady) {
                try {
                    mStartLock.wait();
                } catch (InterruptedException ie) { /* not expected */ }
            }
        }
    }

    /**
     * Shuts everything down.
     */
    public void shutdown() {
        Log.d(TAG, "shutdown");
        Looper.myLooper().quit();
    }

    /**
     * Returns the render thread's Handler.  This may be called from any thread.
     */
    public RenderHandler getHandler() {
        return mHandler;
    }

    /**
     * Handles the surface-created callback from SurfaceView.  Prepares GLES and the Surface.
     */
    public void surfaceAvailable(SurfaceHolder holder, boolean newSurface) {
        Surface surface = holder.getSurface();
        mWindowSurface = new WindowSurface(mEglCore, surface, false);
        mWindowSurface.makeCurrent();

        // Create and configure the SurfaceTexture, which will receive frames from the
        // camera.  We set the textured rect's program to render from it.
        mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
        int textureId = mTexProgram.createTextureObject();
        mCameraTexture = new SurfaceTexture(textureId);
        mRect.setTexture(textureId);

        if (!newSurface) {
            // This Surface was established on a previous run, so no surfaceChanged()
            // message is forthcoming.  Finish the surface setup now.
            //
            // We could also just call this unconditionally, and perhaps do an unnecessary
            // bit of reallocating if a surface-changed message arrives.
            mWindowSurfaceWidth = mWindowSurface.getWidth();
            mWindowSurfaceHeight = mWindowSurface.getHeight();
            finishSurfaceSetup();
        }

        mCameraTexture.setOnFrameAvailableListener(this);
    }

    /**
     * Releases most of the GL resources we currently hold (anything allocated by
     * surfaceAvailable()).
     * <p>
     * Does not release EglCore.
     */
    private void releaseGl() {
        GlUtil.checkGlError("releaseGl start");

        if (mWindowSurface != null) {
            mWindowSurface.release();
            mWindowSurface = null;
        }
        if (mTexProgram != null) {
            mTexProgram.release();
            mTexProgram = null;
        }
        GlUtil.checkGlError("releaseGl done");

        mEglCore.makeNothingCurrent();
    }

    /**
     * Handles the surfaceChanged message.
     * <p>
     * We always receive surfaceChanged() after surfaceCreated(), but surfaceAvailable()
     * could also be called with a Surface created on a previous run.  So this may not
     * be called.
     */
    public void surfaceChanged(int width, int height) {
        Log.d(TAG, "RenderThread surfaceChanged " + width + "x" + height);

        mWindowSurfaceWidth = width;
        mWindowSurfaceHeight = height;
        finishSurfaceSetup();
    }

    /**
     * Handles the surfaceDestroyed message.
     */
    public void surfaceDestroyed() {
        // In practice this never appears to be called -- the activity is always paused
        // before the surface is destroyed.  In theory it could be called though.
        Log.d(TAG, "RenderThread surfaceDestroyed");
        releaseGl();
    }

    /**
     * Sets up anything that depends on the window size.
     * <p>
     * Open the camera (to set mCameraAspectRatio) before calling here.
     */
    private void finishSurfaceSetup() {
        int width = mWindowSurfaceWidth;
        int height = mWindowSurfaceHeight;
        Log.d(TAG, "finishSurfaceSetup size=" + width + "x" + height +
                " camera=" + mCameraPreviewWidth + "x" + mCameraPreviewHeight);

        // Use full window.
        GLES20.glViewport(0, 0, width, height);

        // Simple orthographic projection, with (0,0) in lower-left corner.
        Matrix.orthoM(mDisplayProjectionMatrix, 0, 0, width, 0, height, -1, 1);

        // Default position is center of screen.
        mPosX = width / 2.0f;
        mPosY = height / 2.0f;

        updateGeometry();

        // Ready to go, start the camera.
        Log.d(TAG, "starting camera preview");
        try {
            mCamera.setPreviewTexture(mCameraTexture);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    /**
     * Updates the geometry of mRect, based on the size of the window and the current
     * values set by the UI.
     */
    private void updateGeometry() {
        int width = mWindowSurfaceWidth;
        int height = mWindowSurfaceHeight;

        int smallDim = Math.min(width, height);
        // Max scale is a bit larger than the screen, so we can show over-size.
        float scaled = smallDim * (mSizePercent / 100.0f) * 1.25f;
        float cameraAspect = (float) mCameraPreviewWidth / mCameraPreviewHeight;
        newWidth = Math.round(scaled * cameraAspect);
        newHeight = Math.round(scaled);

        float zoomFactor = 1.0f - (mZoomPercent / 100.0f);
        int rotAngle = Math.round(360 * (mRotatePercent / 100.0f));

        mRect.setScale(newWidth, newHeight);
        mRect.setPosition(mPosX, mPosY);
        mRect.setRotation(rotAngle);
        mRectDrawable.setScale(zoomFactor);

        mMainHandler.sendRectSize(newWidth, newHeight);
        mMainHandler.sendZoomArea(Math.round(mCameraPreviewWidth * zoomFactor),
                Math.round(mCameraPreviewHeight * zoomFactor));
        mMainHandler.sendRotateDeg(rotAngle);
    }

    @Override   // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        mHandler.sendFrameAvailable();
    }

    /**
     * Handles incoming frame of data from the camera.
     */
    public void frameAvailable() {
        mCameraTexture.updateTexImage();
        draw();

//        synchronized (lock) {
//            try {
                saveTextureToBitmap();

//                lock.wait();
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }

    }

    public void unlockThread(BmpProducer outPixels, BmpProducer.Callbacks callbacks){
        outPixels.loadBitmaps(buffer.array(),mWindowSurfaceWidth,mWindowSurfaceHeight, callbacks);
        waitingUntilLoaded = false;

        Log.e(TAG, "unlockThread restarted");
    }

    private void saveTextureToBitmap() {
        if( waitingUntilLoaded ){
//            Log.e(TAG, "waitingUntilLoaded started");
            return;
        }

        waitingUntilLoaded = true;



        buffer = ByteBuffer.allocate(mWindowSurfaceWidth * mWindowSurfaceHeight * 4);
        GLES20.glReadPixels(0, 0, mWindowSurfaceWidth, mWindowSurfaceHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
//        bitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
//        bitmap.copyPixelsFromBuffer(buffer);
//
//        try (FileOutputStream out = new FileOutputStream("/sdcard/my_jpg.jpg")) {
//            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out); // bmp is your Bitmap instance
//            // PNG is a lossless format, the compression factor (100) is ignored
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
    }

    /**
     * Draws the scene and submits the buffer.
     */
    public void draw() {
        GlUtil.checkGlError("draw start");

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        mRect.draw(mTexProgram, mDisplayProjectionMatrix);
        mWindowSurface.swapBuffers();




//        Log.e(TAG, "looper quit");

        GlUtil.checkGlError("draw done");
    }

    public void setZoom(int percent) {
        mZoomPercent = percent;
        updateGeometry();
    }

    public void setSize(int percent) {
        mSizePercent = percent;
        updateGeometry();
    }

    public void setRotate(int percent) {
        mRotatePercent = percent;
        updateGeometry();
    }

    public void setPosition(int x, int y) {
        mPosX = x;
        mPosY = mWindowSurfaceHeight - y;   // GLES is upside-down
        updateGeometry();
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width
     * and height with a fixed frame rate.
     * <p>
     * Sets mCameraPreviewWidth / mCameraPreviewHeight.
     */
    private void openCamera(int desiredWidth, int desiredHeight, int desiredFps) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        // Try to find a front-facing camera (e.g. for videoconferencing).
        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                mCamera = Camera.open(i);
                break;
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);

        // Try to set the frame rate to a constant value.
        int thousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000);

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true);

        mCamera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }
        Log.i(TAG, "Camera config: " + previewFacts);

        mCameraPreviewWidth = mCameraPreviewSize.width;
        mCameraPreviewHeight = mCameraPreviewSize.height;
        mMainHandler.sendCameraParams(mCameraPreviewWidth, mCameraPreviewHeight,
                thousandFps / 1000.0f);
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }
}


