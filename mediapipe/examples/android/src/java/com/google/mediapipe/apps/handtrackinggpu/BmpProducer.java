package com.google.mediapipe.apps.handtrackinggpu;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.Toast;


public class BmpProducer extends Thread {

    public static final long THREAD_DELAY = 200;
    private final Context context;
    CustomFrameAvailableListner customFrameAvailableListner;

    public int height = 513,width = 513;
    byte[] pixels;
    Callbacks callbacks = null;


    BmpProducer(Context context, Callbacks callbacks){
        Toast.makeText(context, "BmpProducer", Toast.LENGTH_SHORT).show();
        this.context = context;
        this.callbacks = callbacks;
//        bmp = BitmapFactory.decodeResource(context.getResources(), R.drawable.img2);
//        bmp = Bitmap.createScaledBitmap(bmp,480,640,true);
//        height = bmp.getHeight();
//        width = bmp.getWidth();
        start();
    }

    public void loadBitmaps(byte[] pixels, int width, int height ){
        this.pixels = pixels;
        this.width = width;
        this.height = height;

    }

    public void setCustomFrameAvailableListner(CustomFrameAvailableListner customFrameAvailableListner){
        this.customFrameAvailableListner = customFrameAvailableListner;
    }

    public static final String TAG="BmpProducer";
    @Override
    public void run() {
        super.run();
        while ((true)){
            if(pixels==null || customFrameAvailableListner == null)
                continue;
            Log.d(TAG,"Writing frame");
            customFrameAvailableListner.onFrame(pixels,width,height);
            /*OTMainActivity.imageView.post(new Runnable() {
                @Override
                public void run() {
                    OTMainActivity.imageView.setImageBitmap(bg);
                }
            });*/

            pixels = null;

            if( this.callbacks != null ){
                this.callbacks.finishdDrawingFrame();
            }

            try{
                Thread.sleep(THREAD_DELAY);
            }catch (Exception e){
                Log.d(TAG,e.toString());
            }
        }
    }

    public interface Callbacks{
        void finishdDrawingFrame();
    }
}
