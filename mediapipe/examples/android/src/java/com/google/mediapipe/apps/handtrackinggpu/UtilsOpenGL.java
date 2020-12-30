package com.google.mediapipe.apps.handtrackinggpu;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class UtilsOpenGL {

	private static final String TAG = "UtilsOpenGL";
    public final static float[] FULL_QUAD_COORDS_0DEGREES = {
            -1, 1,
            -1, -1,
            1, 1,
            1, -1
    };
    public final static float[] FULL_QUAD_COORDS_180DEGREES =
            {
                    1, -1,
                    1, 1,
                    -1, -1,
                    -1, 1
            };
//	public final static float OTSU_BOTTOM_THRESH = 0.22f ;
	public final static float OTSU_BOTTOM_THRESH = 0.35f ;
	public final static float OTSU_TOP_THRESH = 0.61f;
	
//	public final static float OTSU_BOTTOM_THRESH = 0.01f; 	
//	public final static float OTSU_TOP_THRESH = 0.99f;


    public static void mattFShader( int programHandle, float otsuValue, float botIncr, float topIncr)
    {
		float bottomThresh = ( otsuValue - botIncr ) < 0.0f ? 0.0f : otsuValue - botIncr ;
		float topThresh = ( otsuValue + topIncr ) > 1.0f ? 1.0f : otsuValue + topIncr;

		int mUniformHandleContrastA = GLES20.glGetUniformLocation(programHandle, "u_ContrastA"); 
		int mUniformHandleContrastB = GLES20.glGetUniformLocation(programHandle, "u_ContrastB");
		int mUniformHandleContrastC = GLES20.glGetUniformLocation(programHandle, "u_ContrastC");

//		Log.e(TAG, String.format("[otsu:] %f [bottom:] %f [top:] %f ", otsuValue,bottomThresh,topThresh ));

		GLES20.glUniform1f(mUniformHandleContrastA, bottomThresh); // Bottom thresh
		GLES20.glUniform1f(mUniformHandleContrastB, topThresh); // Top thresh
		GLES20.glUniform1f(mUniformHandleContrastC, otsuValue); // Otsu

    }
    
    public static void mattFShaderFixed( int programHandle)
    {
		int mUniformHandleContrastA = GLES20.glGetUniformLocation(programHandle, "u_ContrastA"); 
		int mUniformHandleContrastB = GLES20.glGetUniformLocation(programHandle, "u_ContrastB");


		GLES20.glUniform1f(mUniformHandleContrastA, OTSU_BOTTOM_THRESH); // Bottom thresh
		GLES20.glUniform1f(mUniformHandleContrastB, OTSU_TOP_THRESH); // Top thresh
    }
    
    public static void mattFShader( int programHandle, float bottomThreshold, float topThreshold)
    {
		int mUniformHandleContrastA = GLES20.glGetUniformLocation(programHandle, "u_ContrastA"); 
		int mUniformHandleContrastB = GLES20.glGetUniformLocation(programHandle, "u_ContrastB");


		GLES20.glUniform1f(mUniformHandleContrastA, bottomThreshold); // Bottom thresh
		GLES20.glUniform1f(mUniformHandleContrastB, topThreshold); // Top thresh
    }

	
	// Getter
	public static float[] computeTexturePositionsShader(float aZoom)
	{
		// Calculate Values based on zoom
//		aZoom = ControllerZoom.capZoom(aZoom);
		float centerOffset = 0.5f / aZoom;
		final float lowerValue = 0.5f - centerOffset;
		final float upperValue = 0.5f + centerOffset;
			
			// Create Storage
		final int length = TEXTURE_POSITIONS_BOOLEAN2.length;
		float[] textPos = new float[TEXTURE_POSITIONS2.length];
			
		// For all points - Select Value
		for (int i = 0; i < length; i++)
		{
			textPos[i] = TEXTURE_POSITIONS_BOOLEAN2[i] ? upperValue : lowerValue;
//			Log.e(TAG, String.format("%d %f", i, textPos[i]));
		}
			
		// Return Result
		return textPos;
	}
	
	public static float[] computeTexturePositionsShader(float aZoom, float widthToHeightRatio)
	{
		// Cap Zoom
//		aZoom = ControllerZoom.capZoom(aZoom);
//		Log.e(TAG, String.format("%f", aZoom));
			
		// Calculate View Size
		float centerOffsetX = 0.5f / aZoom;
		float centerOffsetY = 0.5f / aZoom;
		if (widthToHeightRatio > 1.0f)
			centerOffsetY /= widthToHeightRatio;
		else
			centerOffsetX *= widthToHeightRatio;
			
		// Calculate View Values
		final float lowerValueX = 0.5f - centerOffsetX;
		final float upperValueX = 0.5f + centerOffsetX;
			final float lowerValueY = 0.5f - centerOffsetY;
			final float upperValueY = 0.5f + centerOffsetY;
					
			// Create Storage
			final int length = TEXTURE_POSITIONS_BOOLEAN2.length;
			float[] textPos = new float[length];
			
			// For All Point Pairs
			for (int pointIndex = 0; pointIndex < length; )
			{
				// X
				textPos[pointIndex] = TEXTURE_POSITIONS_BOOLEAN2[pointIndex] ? upperValueX : lowerValueX;
//				Log.e(TAG, String.format("%d %f",pointIndex, textPos[pointIndex]));
				pointIndex++;
				
				
				// Y
				textPos[pointIndex] = TEXTURE_POSITIONS_BOOLEAN2[pointIndex] ? upperValueY : lowerValueY;
//				Log.e(TAG, String.format("%d %f", pointIndex, textPos[pointIndex]));
				pointIndex++;
				
			}
					
			// Return Result
			return textPos;
		}
		
		private static final boolean[] TEXTURE_POSITIONS_BOOLEAN2 = 
			{	// S, T (or X, Y)
				false, false,
				false, true,
				true,  false,
				true,  true,
			};
			
			// Constants
			private static final float[] TEXTURE_POSITIONS2 =
			{	// S, T (or X, Y)											
				0.0f, 0.0f, 
				0.0f, 1.0f,
				1.0f, 0.0f,	
				1.0f, 1.0f,
			};
			
			public static FloatBuffer initDigitalZoom(float[] texturePositions)
			{
				FloatBuffer mTextureCoordinates = ByteBuffer
						.allocateDirect(texturePositions.length * 4)
						.order(ByteOrder.nativeOrder()).asFloatBuffer();
				mTextureCoordinates.put(texturePositions).position(0);
				return mTextureCoordinates;
			}
			

}
