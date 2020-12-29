package com.google.mediapipe.apps.handtrackinggpu;

import android.graphics.Bitmap;


public interface CustomFrameAvailableListner {

    public void onFrame(byte[] pixels, int width, int height);
}
