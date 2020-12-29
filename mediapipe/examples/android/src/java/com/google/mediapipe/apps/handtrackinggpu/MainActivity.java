package com.google.mediapipe.apps.handtrackinggpu;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

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


/** Main activity of MediaPipe example apps. */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private static final String BINARY_GRAPH_NAME = "hand_tracking_mobile_gpu.binarypb";
    private static final String INPUT_VIDEO_STREAM_NAME = "input_video";
    private static final String OUTPUT_VIDEO_STREAM_NAME = "output_video";
    private static final String INPUT_NUM_HANDS_SIDE_PACKET_NAME = "num_hands";
    private static final String OUTPUT_LANDMARKS_STREAM_NAME = "hand_landmarks";
    private static final int NUM_HANDS = 2;
    private static final boolean FLIP_FRAMES_VERTICALLY = true;

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


    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private SurfaceTexture previewFrameTexture;
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
        setContentView(R.layout.activity_main);
        previewDisplayView = new SurfaceView(this);
        setupPreviewDisplayView();

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
        super.onResume();
        converter = new BitmapConverter(eglManager.getContext());
        //converter.setFlipY(FLIP_FRAMES_VERTICALLY);
        converter.setConsumer(processor);
        startProducer();

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                loadImage(null);
            }
        },1000,1000);

    }

    @Override
    protected void onPause() {
        super.onPause();
        converter.close();
    }


    private void setupPreviewDisplayView() {
        previewDisplayView.setVisibility(View.GONE);
        ViewGroup viewGroup = findViewById(R.id.preview_display_layout);
        viewGroup.addView(previewDisplayView);

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
    }

    private void startProducer(){
        bitmapProducer = new BmpProducer(this);
        previewDisplayView.setVisibility(View.VISIBLE);
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

    public void loadImage(View view) {

        if( imgCounter % 5 == 0) {
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img3);
            bmp = Bitmap.createScaledBitmap(bmp, 480, 640, true);
            bitmapProducer.loadBitmaps(bmp);
        }else if( imgCounter % 5 == 1){
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img4);
            bmp = Bitmap.createScaledBitmap(bmp,480,640,true);
            bitmapProducer.loadBitmaps(bmp);
        }else if( imgCounter % 5 == 2){
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img1);
            bmp = Bitmap.createScaledBitmap(bmp,480,640,true);
            bitmapProducer.loadBitmaps(bmp);
        }else if( imgCounter % 5 == 3){
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img5);
            bmp = Bitmap.createScaledBitmap(bmp,480,640,true);
            bitmapProducer.loadBitmaps(bmp);
        }else if( imgCounter % 5 == 4){
            Bitmap bmp = BitmapFactory.decodeResource(getResources(), R.drawable.img2);
            bmp = Bitmap.createScaledBitmap(bmp,480,640,true);
            bitmapProducer.loadBitmaps(bmp);
        }

        imgCounter++;
    }


}

