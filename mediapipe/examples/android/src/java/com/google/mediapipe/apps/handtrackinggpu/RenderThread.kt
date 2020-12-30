package com.google.mediapipe.apps.handtrackinggpu;

import android.app.Activity
import android.content.Intent
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder



import java.io.IOException


/**
 * Thread that handles all rendering and camera operations.
 */
class RenderThread
/**
 * Constructor.  Pass in the MainHandler, which allows us to send stuff back to the
 * Activity.
 */(private val mActivity: Activity, private var mCamera: Camera?, private val mMainHandler: MainHandler) : Thread(), SurfaceTexture.OnFrameAvailableListener {
    /**
     * Returns the render thread's Handler.  This may be called from any thread.
     */
    //    public static final int REQ_CAMERA_FPS = 60;
    // Object must be created on render thread to get correct Looper, but is used from
    // UI thread, so we need to declare it volatile to ensure the UI thread sees a fully
    // constructed object.
    @Volatile
    lateinit var handler: RenderHandler
        private set

    // Used to wait for the thread to start.
    private val mStartLock = java.lang.Object()
    private var mReady = false
    private var mCameraPreviewWidth = 0
    private var mCameraPreviewHeight = 0
    private var mEglCore: EglCore? = null
    private var mWindowSurface: WindowSurface? = null
    private var mWindowSurfaceWidth = 0
    private var mWindowSurfaceHeight = 0

    // Receives the output from the camera preview.
    private var mCameraTexture: SurfaceTexture? = null

    // Orthographic projection matrix.
    private val mDisplayProjectionMatrix = FloatArray(16)
    private var mTexProgram: Texture2dProgram? = null
    private val mRectDrawable = ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE)
    private val mRect = Sprite2d(mRectDrawable)
    private val mRectDrawable2 = ScaledDrawable2d(Drawable2d.Prefab.RECTANGLE)
    private val mRect2 = Sprite2d(mRectDrawable2)
    private val mZoomPercent = DEFAULT_ZOOM_PERCENT
    private var mSizePercent = DEFAULT_SIZE_PERCENT
    private var mRotatePercent = DEFAULT_ROTATE_PERCENT
    private var mPosX = 0f
    private var mPosY = 0f
    private var mPosX2 = 0f
    private var mPosY2 = 0f
    private var mChangeColor = false
    private var mHolder: SurfaceHolder? = null
    private var mNewSurface = false
    private var mColor = Texture2dProgram.ProgramType.ORIGINAL
    private var takingPicture = false
    private var startTime: Long = 0
    private var fpsCount = 0
    private var mZoomIndex = 0
    private var mEyeMode = VisionMode.SHOW_BOTH_EYES
    private var mFrameCounter = 0

    /**
     * Thread entry point.
     */
    override fun run() {
        Looper.prepare()

        // We need to create the Handler before reporting ready.
        handler = RenderHandler(this)
        synchronized(mStartLock) {
            mReady = true
            mStartLock.notify() // signal waitUntilReady()
        }

        // Prepare EGL and open the camera before we start handling messages.
        mEglCore = EglCore(null, 0)
        openCamera(REQ_CAMERA_WIDTH, REQ_CAMERA_HEIGHT, REQ_CAMERA_FPS)
        Looper.loop()
        Log.d(TAG, "looper quit")
        releaseCamera()
        releaseGl()
        mEglCore!!.release()
        synchronized(mStartLock) { mReady = false }
    }

    /**
     * Waits until the render thread is ready to receive messages.
     *
     *
     * Call from the UI thread.
     */
    fun waitUntilReady() {
        synchronized(mStartLock) {
            while (!mReady) {
                try {
                    mStartLock.wait()
                } catch (ie: InterruptedException) { /* not expected */
                }
            }
        }
    }

    /**
     * Shuts everything down.
     */
    fun shutdown() {
        Log.d(TAG, "shutdown")
        Looper.myLooper().quit()
    }

    /**
     * Handles the surface-created callback from SurfaceView.  Prepares GLES and the Surface.
     */
    fun surfaceAvailable(holder: SurfaceHolder, newSurface: Boolean) {
        mHolder = holder
        mNewSurface = newSurface
        val surface = holder.surface
        mWindowSurface = WindowSurface(mEglCore, surface, false)
        mWindowSurface!!.makeCurrent()

        // Create and configure the SurfaceTexture, which will receive frames from the
        // camera.  We set the textured rect's program to render from it.
//        mTexProgram = new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT);
        try {
            mTexProgram = Texture2dProgram(Texture2dProgram.ProgramType.values()[0], mActivity)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mEyeMode = VisionMode.values()[0]
        val textureId = mTexProgram!!.createTextureObject()
        mCameraTexture = SurfaceTexture(textureId)
        mRect.setTexture(textureId)
        mRect2.setTexture(textureId)
        if (!newSurface) {
            // This Surface was established on a previous run, so no surfaceChanged()
            // message is forthcoming.  Finish the surface setup now.
            //
            // We could also just call this unconditionally, and perhaps do an unnecessary
            // bit of reallocating if a surface-changed message arrives.
//            mWindowSurfaceWidth = mWindowSurface.getWidth();
            mWindowSurfaceWidth = mWindowSurface!!.width
            mWindowSurfaceHeight = mWindowSurface!!.height
            finishSurfaceSetup()
        }
        mCameraTexture!!.setOnFrameAvailableListener(this)
    }

    /**
     * Releases most of the GL resources we currently hold (anything allocated by
     * surfaceAvailable()).
     *
     *
     * Does not release EglCore.
     */
    fun releaseGl() {
        GlUtil.checkGlError("releaseGl start")
        if (mWindowSurface != null) {
            mWindowSurface!!.release()
            mWindowSurface = null
        }
        if (mTexProgram != null) {
            mTexProgram!!.release()
            mTexProgram = null
        }
        GlUtil.checkGlError("releaseGl done")
        mEglCore!!.makeNothingCurrent()
    }

    /**
     * Handles the surfaceChanged message.
     *
     *
     * We always receive surfaceChanged() after surfaceCreated(), but surfaceAvailable()
     * could also be called with a Surface created on a previous run.  So this may not
     * be called.
     */
    fun surfaceChanged(width: Int, height: Int) {
        Log.d(TAG, "RenderThread surfaceChanged " + width + "x" + height)
        mWindowSurfaceWidth = width
        mWindowSurfaceHeight = height
        finishSurfaceSetup()
    }

    /**
     * Handles the surfaceDestroyed message.
     */
    fun surfaceDestroyed() {
        // In practice this never appears to be called -- the activity is always paused
        // before the surface is destroyed.  In theory it could be called though.
        Log.d(TAG, "RenderThread surfaceDestroyed")
        releaseGl()
    }

    /**
     * Sets up anything that depends on the window size.
     *
     *
     * Open the camera (to set mCameraAspectRatio) before calling here.
     */
    fun finishSurfaceSetup() {
        val width = mWindowSurfaceWidth
        val height = mWindowSurfaceHeight
        Log.d(TAG, "finishSurfaceSetup size=" + width + "x" + height +
                " camera=" + mCameraPreviewWidth + "x" + mCameraPreviewHeight)

        // Use full window.
        GLES20.glViewport(0, 0, width, height)

        // Simple orthographic projection, with (0,0) in lower-left corner.
        Matrix.orthoM(mDisplayProjectionMatrix, 0, 0f, width.toFloat(), 0f, height.toFloat(), -1f, 1f)

        // Default position is center of screen.
        val tmpHeight = height / 2.0f // - height * 0.01f;
        mPosX = width / 2.0f
        mPosY = tmpHeight

        // Default position is center of screen.
        mPosX2 = width / 2.0f
        mPosY2 = tmpHeight
        updateGeometry()

        // Ready to go, start the camera.
        Log.d(TAG, "starting camera preview")
        try {
            mCamera!!.setPreviewTexture(mCameraTexture)
        } catch (ioe: IOException) {
            throw RuntimeException(ioe)
        }
        mCamera!!.parameters
        mCamera!!.startPreview()
    }

    /**
     * Updates the geometry of mRect, based on the size of the window and the current
     * values set by the UI.
     */
    fun updateGeometry() {
        val width = mWindowSurfaceWidth
        val height = mWindowSurfaceHeight
        val smallDim = Math.min(width, height)
        // Max scale is a bit larger than the screen, so we can show over-size.
        val scaled = smallDim * (mSizePercent / 100.0f) * 1.25f
        val cameraAspect = mCameraPreviewWidth.toFloat() / mCameraPreviewHeight
        val newWidth = Math.round(scaled * cameraAspect)
        val newHeight = Math.round(scaled)
        val zoomFactor = 1.0f - mZoomPercent / 100.0f
        val rotAngle = Math.round(360 * (mRotatePercent / 100.0f))
        val display = mMainHandler.activity.get()!!.windowManager.defaultDisplay
        val size = Point()
        display.getSize(size)
        var rectHeight = 0
        rectHeight = if (mZoomIndex == -40) (Globals.SCREEN_HEIGHT.toDouble() * 0.25).toInt() else if (mZoomIndex == -30) (Globals.SCREEN_HEIGHT.toDouble() * 0.50).toInt() else if (mZoomIndex == -20) (Globals.SCREEN_HEIGHT.toDouble() * 0.60).toInt() else if (mZoomIndex == -10) (Globals.SCREEN_HEIGHT.toDouble() * 0.70).toInt() else if (mZoomIndex == 110) (Globals.SCREEN_HEIGHT.toDouble() * 1.05).toInt() else if (mZoomIndex == 120) (Globals.SCREEN_HEIGHT.toDouble() * 1.15).toInt() else (Globals.SCREEN_HEIGHT.toDouble() * 0.95).toInt()
        val widthScreen = (rectHeight.toDouble() * (REQ_CAMERA_WIDTH.toDouble() / REQ_CAMERA_HEIGHT.toDouble())).toInt() //Globals.SCREEN_WIDTH;
        val heightScreen = rectHeight
        var offset = 0
        offset = if (Globals.SCREEN_WIDTH / 2 > widthScreen) widthScreen / 2 + (Globals.SCREEN_WIDTH / 2 - widthScreen) / 2 else widthScreen / 2
        if (mZoomIndex <= -10) {
            val borderOffset = (Globals.SCREEN_HEIGHT.toDouble() * 0.15 / 2).toInt()
            offset += borderOffset
        }
        mRect.setScale(widthScreen.toFloat(), heightScreen.toFloat())
        mRect.setPosition(offset.toFloat(), mPosY)
        mRect.rotation = rotAngle.toFloat()
        mRectDrawable.setScale(zoomFactor)
        mRect2.setScale(widthScreen.toFloat(), heightScreen.toFloat())
        mRect2.setPosition(offset + Globals.SCREEN_WIDTH / 2.toFloat(), mPosY2)
        mRect2.rotation = rotAngle.toFloat()
        mRectDrawable2.setScale(zoomFactor)
        mMainHandler.sendRectSize(newWidth, newHeight)
        mMainHandler.sendZoomArea(Math.round(mCameraPreviewWidth * zoomFactor),
                Math.round(mCameraPreviewHeight * zoomFactor))
        mMainHandler.sendRotateDeg(rotAngle)


        //UtilsDebug.debug(String.format("width: %d, height: %d",  widthScreen,heightScreen ));
    }

    // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
//    	UtilsDebug.debug("onFrameAvailable");
        if (mChangeColor) {
            try {
                mTexProgram = Texture2dProgram(mColor, mActivity)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mChangeColor = false
        }
        handler.sendFrameAvailable()
    }

    /**
     * Handles incoming frame of data from the camera.
     */
    fun frameAvailable() {
        mCameraTexture!!.updateTexImage()
        draw()
    }

    /**
     * Draws the scene and submits the buffer.
     */
    fun draw() {
//    	UtilsDebug.debug("draw");
        GlUtil.checkGlError("draw start")
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (mEyeMode == VisionMode.SHOW_BOTH_EYES) {
            mRect.draw(mTexProgram, mDisplayProjectionMatrix)
            mRect2.draw(mTexProgram, mDisplayProjectionMatrix)
        } else if (mEyeMode == VisionMode.LEFT_EYE_ONLY) {
            mRect.draw(mTexProgram, mDisplayProjectionMatrix)
        } else if (mEyeMode == VisionMode.RIGHT_EYE_ONLY) {
            mRect2.draw(mTexProgram, mDisplayProjectionMatrix)
        } else if (mEyeMode == VisionMode.HIDE_BOTH_EYES) {
            // Show nothing
        }
        mWindowSurface!!.swapBuffers()
        GlUtil.checkGlError("draw done")

//        debugFPS();
    }

    fun debugFPS() {
        if (System.currentTimeMillis() - startTime >= 1000) {
            Log.e("FPS:", fpsCount.toString())
            //			UtilsDebug.debugOverlay(mActivity, "FPS:" + String.valueOf(fpsCount));
            startTime = System.currentTimeMillis()
            fpsCount = 0
        } else fpsCount++
    }

    fun setZoom(percent: Int) {
//        mZoomPercent = percent;
//        updateGeometry();
        var percent = percent

        mZoomIndex = percent
        updateGeometry()

        if (mCamera == null) {

            return
        }
        val params = mCamera!!.parameters
        val MAX_ZOOM = params.maxZoom
        percent = Math.min(percent, MAX_ZOOM)
        percent = Math.max(percent, 0)
        params.zoom = percent
        mCamera!!.parameters = params
    }

    fun hideRect(pMode: VisionMode) {
//        mZoomPercent = percent;
//        updateGeometry();
        mEyeMode = pMode
        updateGeometry()
    }

    fun setFocus(callback: Camera.AutoFocusCallback?) {

        if (mCamera == null) {

            return
        }
        mCamera!!.autoFocus(callback)
    }

    fun takePicture(activity: Activity?) {
        // TODO Auto-generated method stub
        if (takingPicture) return
        takingPicture = true
    }

    fun setSize(percent: Int) {
        mSizePercent = percent
        updateGeometry()
    }

    fun setRotate(percent: Int) {
        mRotatePercent = percent
        updateGeometry()
    }

    fun setPosition(x: Int, y: Int) {
        mPosX = x.toFloat()
        mPosY = mWindowSurfaceHeight - y.toFloat() // GLES is upside-down
        updateGeometry()
    }

    fun setPosition2(x: Int, y: Int) {
        mPosX2 = x.toFloat()
        mPosY2 = mWindowSurfaceHeight - y.toFloat() // GLES is upside-down
        updateGeometry()
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width
     * and height with a fixed frame rate.
     *
     *
     * Sets mCameraPreviewWidth / mCameraPreviewHeight.
     */
    fun openCamera(desiredWidth: Int, desiredHeight: Int, desiredFps: Int) {
        val parms = mCamera!!.parameters
        val cvtFPS = desiredFps * 1000
        if (desiredFps == 60) {
            parms["fast-fps-mode"] = 1
            parms.setPreviewFpsRange(cvtFPS, cvtFPS)
            parms.setRecordingHint(true) // Better performance when recording
            parms.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        } else {
            parms.setPreviewFpsRange(cvtFPS, cvtFPS)
            parms.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        }
        val supportedPreviewFpsRange = parms.supportedPreviewFpsRange
        for (fps in supportedPreviewFpsRange) {

        }
        val supportedPreviewSizes = parms.supportedPreviewSizes
        for (fps in supportedPreviewSizes) {

        }
        parms.setPreviewSize(REQ_CAMERA_WIDTH, REQ_CAMERA_HEIGHT)
        parms.setPictureSize(3264, 2448)
        parms.videoStabilization = false
        mCamera!!.parameters = parms
        val fpsRange = IntArray(2)
        val mCameraPreviewSize = parms.previewSize
        parms.getPreviewFpsRange(fpsRange)
        var previewFacts = mCameraPreviewSize.width.toString() + "x" + mCameraPreviewSize.height
        previewFacts += if (fpsRange[0] == fpsRange[1]) {
            " @" + fpsRange[0] / 1000.0 + "fps"
        } else {
            " @[" + fpsRange[0] / 1000.0 +
                    " - " + fpsRange[1] / 1000.0 + "] fps"
        }
        Log.i(TAG, "Camera config: $previewFacts")
        mCameraPreviewWidth = mCameraPreviewSize.width
        mCameraPreviewHeight = mCameraPreviewSize.height
        if ( Globals.ENABLE_QR_CODE == false) {
            Log.e(LOG_TAG, "QR CODE DISABLED WHILE DEBUGGING")
//            Toast.makeText(mActivity, "QR CODE DISABLED WHILE DEBUGGING", Toast.LENGTH_LONG).show()
        } else {
            mCamera!!.setPreviewCallback { data, camera ->

            }
        }
        mMainHandler.sendCameraParams(mCameraPreviewWidth, mCameraPreviewHeight,
                60000 / 1000.0f)
    }

    private fun foundQRCode(qrIndex: String) {
        val qrIntent = Intent()
        qrIntent.action = FOUND_QR_CODE
        qrIntent.putExtra("qr_code", qrIndex)
        mActivity.sendBroadcast(qrIntent)
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    fun releaseCamera() {
        if (mCamera != null) {
            mCamera!!.setPreviewCallback(null)
            mCamera!!.stopPreview()
            mCamera!!.release()
            mCamera = null
            Log.d(TAG, "releaseCamera -- done")
        }
    }

    fun setColor(obj: Texture2dProgram.ProgramType) {
        // TODO Auto-generated method stub
        mColor = obj
        mChangeColor = true
    }

    fun setFocusMode(focusmode: String?) {
        // TODO Auto-generated method stub
        if (mCamera == null) return
        val parms = mCamera!!.parameters

//       	parms.setFocusMode(focusmode);
        mCamera!!.parameters = parms
    }

    fun toggleLight(lightmode: String?) {
        // TODO Auto-generated method stub
        // TODO Auto-generated method stub
        if (mCamera == null) return
        val parms = mCamera!!.parameters
        parms.flashMode = lightmode
        mCamera!!.parameters = parms
    }

    enum  class VisionMode {
        SHOW_BOTH_EYES, LEFT_EYE_ONLY, RIGHT_EYE_ONLY, HIDE_BOTH_EYES
    }

    companion object {

        const val SCAN_MODES = "SCAN_MODES"
        const val SCAN_RESULT = "SCAN_RESULT"
        const val SCAN_RESULT_TYPE = "SCAN_RESULT_TYPE"
        const val ERROR_INFO = "ERROR_INFO"
        private val TAG: String? = null
        const val DEFAULT_ZOOM_PERCENT = 0 // 0-100
        const val DEFAULT_SIZE_PERCENT = 100 // 0-100
        const val DEFAULT_ROTATE_PERCENT = 0 // 0-100

        // Requested values; actual may differ.
//        const val REQ_CAMERA_WIDTH = 1088
//        const val REQ_CAMERA_HEIGHT = 1088

        const val REQ_CAMERA_WIDTH = 720
        const val REQ_CAMERA_HEIGHT = 720

        const val REQ_CAMERA_FPS = 30
        private val LOG_TAG = RenderThread::class.java.simpleName
        const val FOUND_QR_CODE = "com.mercury.intent.action.FOUND_QR_CODE"

        /** Set picture quality  */
        fun setMaximumPreviewQuality(mCamera: Camera?, aspectRatio: Double): Camera.Size? {
            if (mCamera == null) return null
            val mParam = mCamera.parameters
            //		 List<Size> sizes = mParam.getSupportedPreviewSizes();
            val sizes = mParam.supportedPictureSizes
            var maxArea = 0
            var maxIndex = -1
            var size: Camera.Size? = null
            for (i in sizes.indices) {
                size = sizes[i]
                val area = size.width * size.height
                val aspect = size.width.toDouble() / size.height.toDouble()
                Log.e(TAG, String.format("%d, %d", size.width, size.height))
                if (area > maxArea && aspect == aspectRatio) {
                    maxArea = area
                    maxIndex = i
                    break
                }
            }
            if (maxIndex != -1) {
                val maxWidth = sizes[maxIndex].width
                val maxHeight = sizes[maxIndex].height
                mParam.setPictureSize(maxWidth, maxHeight)
                try {
                    mCamera.parameters = mParam
                } catch (e: Exception) {
                    Log.e(TAG, "setMaximumPreviewQuality: FAILED")
                }
            }
            return size
        }
    }

}