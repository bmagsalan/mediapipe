package com.google.mediapipe.apps.handtrackinggpu;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.widget.Toast;


public class BmpProducer extends Thread {

    private final Context context;
    CustomFrameAvailableListner customFrameAvailableListner;

    public int height = 513,width = 513;
    Bitmap bmp;
    byte[] pixels;
    int pxWidth;
    int pxHeight;


    BmpProducer(Context context){
        Toast.makeText(context, "BmpProducer", Toast.LENGTH_SHORT).show();
        this.context = context;
//        bmp = BitmapFactory.decodeResource(context.getResources(), R.drawable.img2);
//        bmp = Bitmap.createScaledBitmap(bmp,480,640,true);
//        height = bmp.getHeight();
//        width = bmp.getWidth();
        start();
    }

    public void loadBitmaps(Bitmap bitmap ){
        bmp = bitmap;
        height = bmp.getHeight();
        width = bmp.getWidth();
    }

    public void setCustomFrameAvailableListner(CustomFrameAvailableListner customFrameAvailableListner){
        this.customFrameAvailableListner = customFrameAvailableListner;
    }

    public static final String TAG="BmpProducer";
    @Override
    public void run() {
        super.run();
        while ((true)){
            if(bmp==null || customFrameAvailableListner == null)
                continue;
            Log.d(TAG,"Writing frame");
            customFrameAvailableListner.onFrame(bmp);
            /*OTMainActivity.imageView.post(new Runnable() {
                @Override
                public void run() {
                    OTMainActivity.imageView.setImageBitmap(bg);
                }
            });*/

            bmp = null;

            try{
                Thread.sleep(1000);
            }catch (Exception e){
                Log.d(TAG,e.toString());
            }
        }
    }
}
