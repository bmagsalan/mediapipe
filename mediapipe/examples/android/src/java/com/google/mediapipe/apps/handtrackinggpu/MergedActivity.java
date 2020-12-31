package com.google.mediapipe.apps.handtrackinggpu;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.formats.proto.LandmarkProto;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.AndroidPacketCreator;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.glutil.EglManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

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
    private static final String TAG = MergedActivity.class.getSimpleName();

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
    private RenderThread mRenderThread;

    // Receives messages from renderer thread.
    private MainHandler mHandler;


    private static final String BINARY_GRAPH_NAME = "hand_tracking_mobile_gpu.binarypb";
    private static final String INPUT_VIDEO_STREAM_NAME = "input_video";
    private static final String OUTPUT_VIDEO_STREAM_NAME = "output_video";
    private static final String INPUT_NUM_HANDS_SIDE_PACKET_NAME = "num_hands";
    private static final String OUTPUT_LANDMARKS_STREAM_NAME = "hand_landmarks";
    private static final int NUM_HANDS = 1;
    private static final boolean FLIP_FRAMES_VERTICALLY = false;

    static {
        // Load all native libraries needed by the app.
        System.loadLibrary("mediapipe_jni");
//        try {
        System.loadLibrary("opencv_java3");
//        } catch (java.lang.UnsatisfiedLinkError e) {
//            // Some example apps (e.g. template matching) require OpenCV 4.
//            System.loadLibrary("opencv_java4");
//        }
    }

    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private SurfaceView previewDisplayView;

    // Creates and manages an {@link EGLContext}.
    private EglManager eglManager;
    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    private FrameProcessor processor;
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private BitmapConverter converter;

    // Handles camera access via the {@link CameraX} Jetpack support library.
    //private CameraXPreviewHelper cameraHelper;
    BmpProducer bitmapProducer;
    private int imgCounter;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture_from_camera);

        mHandler = new MainHandler(this);

        SurfaceView sv = (SurfaceView) findViewById(R.id.cameraOnTexture_surfaceView);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this);

        previewDisplayView = new SurfaceView(this);
        previewDisplayView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout rootView = (LinearLayout)findViewById(R.id.root_view);
        rootView.addView(previewDisplayView);

        previewDisplayView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
                            }

                            @Override
                            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                                bitmapProducer.setCustomFrameAvailableListner(converter);
                            }

                            @Override
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(null);
                            }
                        });

        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(this);

        eglManager = new EglManager(null);
        processor =
                new FrameProcessor(
                        this,
                        eglManager.getNativeContext(),
                        BINARY_GRAPH_NAME,
                        INPUT_VIDEO_STREAM_NAME,
                        OUTPUT_VIDEO_STREAM_NAME);
        processor.getVideoSurfaceOutput().setFlipY(FLIP_FRAMES_VERTICALLY);

        processor.addPacketCallback(
                OUTPUT_LANDMARKS_STREAM_NAME,
                (packet) -> {
                    Log.d(TAG, "Received multi-hand landmarks packet.");
                    List<LandmarkProto.NormalizedLandmarkList> multiHandLandmarks =
                            PacketGetter.getProtoVector(packet, LandmarkProto.NormalizedLandmarkList.parser());
                    Log.d(
                            TAG,
                            "[TS:"
                                    + packet.getTimestamp()
                                    + "] "
                                    + getMultiHandLandmarksDebugString(multiHandLandmarks));
                });

        AndroidPacketCreator packetCreator = processor.getPacketCreator();
        Map<String, Packet> inputSidePackets = new HashMap<>();
        inputSidePackets.put(INPUT_NUM_HANDS_SIDE_PACKET_NAME, packetCreator.createInt32(NUM_HANDS));
        processor.setInputSidePackets(inputSidePackets);


    }



    @Override
    protected void onResume() {
        Log.d(TAG, "onResume BEGIN");
        super.onResume();


        mRenderThread = new RenderThread(mHandler);
        mRenderThread.setName("TexFromCam Render");
        mRenderThread.start();
        mRenderThread.waitUntilReady();

        RenderHandler rh = mRenderThread.getHandler();
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

        converter = new BitmapConverter(eglManager.getContext());
        converter.setConsumer(processor);
        bitmapProducer = new BmpProducer(this);


        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (mRenderThread != null) {
                    RenderHandler rh = mRenderThread.getHandler();
                    StructPixelFrame structPixelFrame = new StructPixelFrame();
                    rh.sendUnlock(bitmapProducer);

                }
            }
        },1000,BmpProducer.THREAD_DELAY);
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause BEGIN");
        super.onPause();

        if (mRenderThread == null) {
            return;
        }
        RenderHandler rh = mRenderThread.getHandler();
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

    public void loadImage(View view) {
        Bitmap bmp = null;
        if( imgCounter % 5 == 0) {
            bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img3);
        }else if( imgCounter % 5 == 1){
            bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img4);
        }else if( imgCounter % 5 == 2){
            bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img1);
        }else if( imgCounter % 5 == 3){
            bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img5);
        }else if( imgCounter % 5 == 4){
            bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img2);
        }

        bmp = Bitmap.createScaledBitmap(bmp,480,640,true);
        bitmapProducer.loadBitmaps(UtilsBitmap.bitmapToRgba(bmp),bmp.getWidth(), bmp.getHeight());

        imgCounter++;
    }

    private String getMultiHandLandmarksDebugString(List<LandmarkProto.NormalizedLandmarkList> multiHandLandmarks) {
        if (multiHandLandmarks.isEmpty()) {
            return "No hand landmarks";
        }
        String multiHandLandmarksStr = "Number of hands detected: " + multiHandLandmarks.size() + "\n";
        int handIndex = 0;
        for (LandmarkProto.NormalizedLandmarkList landmarks : multiHandLandmarks) {
            multiHandLandmarksStr +=
                    "\t#Hand landmarks for hand[" + handIndex + "]: " + landmarks.getLandmarkCount() + "\n";
            int landmarkIndex = 0;
            for (LandmarkProto.NormalizedLandmark landmark : landmarks.getLandmarkList()) {
                multiHandLandmarksStr +=
                        "\t\tLandmark ["
                                + landmarkIndex
                                + "]: ("
                                + landmark.getX()
                                + ", "
                                + landmark.getY()
                                + ", "
                                + landmark.getZ()
                                + ")\n";
                ++landmarkIndex;
            }
            ++handIndex;
        }
        return multiHandLandmarksStr;
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
            RenderHandler rh = mRenderThread.getHandler();
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
            RenderHandler rh = mRenderThread.getHandler();
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
            RenderHandler rh = mRenderThread.getHandler();
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
        int action = e.getAction();
        if( action == MotionEvent.ACTION_UP ){
//            loadImage(null);

        }

        return true;
    }
}