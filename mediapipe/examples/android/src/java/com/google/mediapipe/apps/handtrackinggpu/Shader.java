package com.google.mediapipe.apps.handtrackinggpu;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;

public class Shader {
	private int mProgram = 0;
	private int mShaderVertex = 0;
	private int mShaderFragment = 0;
	private String vertexSource;
	private String fragmentSource;
	
	//hashmap for storing uniform/attribute handles
	private final HashMap<String, Integer> mShaderHandleMap = new HashMap<String, Integer>();
	
	
	
	
	public Shader() {
		// TODO Auto-generated constructor stub
	}
	
	public void setProgram(int fragmentShader, Context context) 
			throws Exception{
		
		vertexSource = MATT_V_SHADER();
		
		fragmentSource = FRAGMENT_SHADER_STRINGS[fragmentShader];
		
		
		
		mShaderVertex = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
		mShaderFragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
		
		int program = GLES20.glCreateProgram();
		if(program != 0){
			GLES20.glAttachShader(program, mShaderVertex);
			GLES20.glAttachShader(program, mShaderFragment);
			GLES20.glLinkProgram(program);
			int[] linkStatus = new int[1];
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
			if(linkStatus[0] != GLES20.GL_TRUE){
				String error = GLES20.glGetProgramInfoLog(program);
				deleteProgram();
				throw new Exception(error);
			}
		}
		
		mProgram = program;
		mShaderHandleMap.clear();
		
	}
	
	public void useProgram(){
		GLES20.glUseProgram(mProgram);
	}
	
	public void deleteProgram(){
		GLES20.glDeleteShader(mShaderVertex);
		GLES20.glDeleteShader(mShaderFragment);
		GLES20.glDeleteProgram(mProgram);
		mProgram = mShaderVertex = mShaderFragment = 0;
	}
	
	public int programHandle(){
		return mProgram;
	}
	
	public int getHandle(String name){
		if(mShaderHandleMap.containsKey(name)){
			return mShaderHandleMap.get(name);
		}
		
		int handle = GLES20.glGetAttribLocation(mProgram, name);
		if(handle == -1){
			handle = GLES20.glGetUniformLocation(mProgram, name);
		}
		if(handle == -1){
			Log.d("GLSL shader", "Could not get attrib location for " + name);
		}else{
			mShaderHandleMap.put(name, handle);
		}
		
		return handle;
	}
	
	public int[] getHandles(String... names){
		int[] res = new int[names.length];
		for(int i = 0; i < names.length; ++i){
			res[i] = getHandle(names[i]);
		}
		
		return res;
	}

	private int loadShader(int shaderType, String source)throws Exception{
		int shader = GLES20.glCreateShader(shaderType);
		if(shader != 0){
			GLES20.glShaderSource(shader, source);
			GLES20.glCompileShader(shader);
			int[] compiled = new int[1];
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
			
			if(compiled[0] == 0){
				String error = GLES20.glGetShaderInfoLog(shader);
				GLES20.glDeleteShader(shader);
				throw new Exception(error);
			}
		}
		
		return shader;
	}
	private String loadRawString(int rawId, Context context) throws Exception{
		InputStream is = context.getResources().openRawResource(rawId);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int len;
		while((len = is.read(buf))!= -1){
			baos.write(buf, 0, len);
		}
		return baos.toString();
	}	
	
	public static String MATT_V_SHADER()
	{
		// Constants
		return  "" 		+
			"attribute vec2 aPosition;" 							+ // Per-vertex position information we will pass in.
			"attribute vec2 a_TexCoordinate;" 						+ // Per-vertex texture coordinate information we will pass in.
						
			"varying vec2 vTextureCoord;" 						+ // This will be passed into the fragment shader.
							
			"void main()" 											+ // The entry point for our vertex shader.
			"{" 													+
			"gl_Position = vec4(aPosition, 0.0, 1.0);" 				+ // Pass through the texture coordinate.
			"vTextureCoord = a_TexCoordinate;" 							+ // Pass through the position variable.												  
			"}" 													+
			"";
		

	}
	
	@Deprecated
	public static String SHADERCAM_V_SHADER(){
		// Constants
		return  "" 		+

			"uniform mat4 uTransformM;" +
			"uniform mat4 uOrientationM;" +
			"uniform vec2 ratios;" +
			"attribute vec2 aPosition;" 							+ // Per-vertex position information we will pass in.
			"attribute vec2 a_TexCoordinate;" 						+ // Per-vertex texture coordinate information we will pass in.
						
			"varying vec2 vTextureCoord;" 						+ // This will be passed into the fragment shader.
							
			"void main()" 											+ // The entry point for our vertex shader.
			"{" 													+
			"gl_Position = vec4(aPosition, 0.0, 1.0);" +
			"vTextureCoord = a_TexCoordinate;" 				+ // Pass through the texture coordinate.
//			"gl_Position.xy *= ratios;" 							+ // Pass through the position variable.												  
			"}" 													+
			"";
	}
	
	private static final String SC_HEADER = 		"#extension GL_OES_EGL_image_external : require\n" 							+ // Required to use External Textures
				"precision mediump float;" 													+ // Set the default precision to medium. 
				
		"uniform samplerExternalOES sTexture;" 							+ // The input texture.
		"uniform sampler2D u_Texture;" 												+ // The contrast lookup table.
		
		"uniform float u_ContrastA;" 												+ // Subtracted Value
		"uniform float u_ContrastB;" 												+ // Multiplied Value
		"uniform float u_ContrastC;" 												+ // Added Value
			
		"varying vec2 vTextureCoord;" 											+ // Interpolated texture coordinate per fragment.
				
		"void main()" 																+ //The entry point for our fragment shader.
		"{" 																		+ 
		""	;
	
//	private static final String SC_GRAYSCALE_FORMULA = "   "; //float grayScale = (tempColor.r);"; // float grayScale = min(tempColor.b,grayScale1);";
	private static final String SC_GRAYSCALE_FORMULA = "   float grayScale = (tempColor.r * 0.299) + (tempColor.g * 0.587) + (tempColor.b *  0.114) ; ";
		
		private static final String SC_BLUE_ON_YELLOW = 
				"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
				SC_GRAYSCALE_FORMULA +
				"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
				"	gl_FragColor = vec4(1.0-binarized,1.0-binarized,binarized,1);" +
				"}" +
				"";
		
		private static final String SC_YELLOW_ON_BLUE =
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
				SC_GRAYSCALE_FORMULA +
		"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
		"	gl_FragColor = vec4(binarized,binarized,1.0-binarized,1);" + // R,G,B
		"}" +

		"";
		
		private static final String SC_BLACK_ON_WHITE = 
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		SC_GRAYSCALE_FORMULA +
		"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
		"	gl_FragColor = vec4(binarized,binarized,binarized,1);" + // R,G,B
		"}" +

		"";
		
		
		private static final String SC_BLACK_ON_YELLOW = 	
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		SC_GRAYSCALE_FORMULA +
		"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
		"	gl_FragColor = vec4(binarized,binarized,0.0,1);" + // R,G,B
		"}" +

		"";
		
		
		
		private static final String SC_YELLOW_ON_BLACK = 	
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		SC_GRAYSCALE_FORMULA +
		"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
		"	gl_FragColor = vec4(1.0-binarized,1.0-binarized,0.0,1);" + // R,G,B
		"}" +

		"";
		
		private static final String SC_WHITE_ON_BLACK = 
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		SC_GRAYSCALE_FORMULA +
		"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
		"	gl_FragColor = vec4(1.0-binarized,1.0-binarized,1.0-binarized,1);" + // R,G,B
		"}" +

		"";
		
		private static final String SC_BLACK_ON_GREEN = 
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		SC_GRAYSCALE_FORMULA +
		"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
		"	gl_FragColor = vec4(0.0,binarized,0.0,1);" + // R,G,B
		"}" +

		"";
		
		private static final String SC_GREEN_ON_BLACK = 
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		SC_GRAYSCALE_FORMULA +
		"   float binarized = smoothstep(u_ContrastA,u_ContrastB,grayScale);" +
		"	gl_FragColor = vec4(0.0,1.0-binarized,0.0,1);" + // R,G,B
		"}" +

		"";
		
		private static final String SC_GRAYSCALE = 
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		SC_GRAYSCALE_FORMULA +
		"	gl_FragColor = vec4(grayScale,grayScale,grayScale,1);" + // R,G,B
		"}" +

		"";
		
		private static final String SC_HIGH_CONTRAST = 	
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		"	gl_FragColor.x = (tempColor.x - u_ContrastC) * 1.50 + u_ContrastC;" 	+	// Set Red   - Contrast Stretched Red
		"	gl_FragColor.y = (tempColor.y - u_ContrastC) * 1.50 + u_ContrastC;" 	+	// Set Green - Contrast Stretched Green
		"	gl_FragColor.z = (tempColor.z - u_ContrastC) * 1.50 + u_ContrastC;" 	+	// Set Blue  - Contrast Stretched Blue
		"}" +


		"";
		
		private static final String SC_ORIGINAL =  
		"	vec4 tempColor = texture2D(sTexture, vTextureCoord);"		+ // Sample the texture value
		"	gl_FragColor = tempColor;	" +
		"}" +


		"";


	private static final String SC_SOBEL = "" +
			"    float x = 1.0 / 1088.0;" +
			"    float y = 1.0 / 1088.0;" +
			"" +
			"" +
			"" +
			"    vec4 horizEdge = vec4( 0.0 );" +
			"    horizEdge -= texture2D( sTexture, vec2( vTextureCoord.x - x, vTextureCoord.y - y ) ) * 1.0;" +
			"    horizEdge -= texture2D( sTexture, vec2( vTextureCoord.x - x, vTextureCoord.y     ) ) * 2.0;" +
			"    horizEdge -= texture2D( sTexture, vec2( vTextureCoord.x - x, vTextureCoord.y + y ) ) * 1.0;" +
			"    horizEdge += texture2D( sTexture, vec2( vTextureCoord.x + x, vTextureCoord.y - y ) ) * 1.0;" +
			"    horizEdge += texture2D( sTexture, vec2( vTextureCoord.x + x, vTextureCoord.y     ) ) * 2.0;" +
			"    horizEdge += texture2D( sTexture, vec2( vTextureCoord.x + x, vTextureCoord.y + y ) ) * 1.0;" +
			"    vec4 vertEdge = vec4( 0.0 );" +
			"    vertEdge -= texture2D( sTexture, vec2( vTextureCoord.x - x, vTextureCoord.y - y ) ) * 1.0;" +
			"    vertEdge -= texture2D( sTexture, vec2( vTextureCoord.x    , vTextureCoord.y - y ) ) * 2.0;" +
			"    vertEdge -= texture2D( sTexture, vec2( vTextureCoord.x + x, vTextureCoord.y - y ) ) * 1.0;" +
			"    vertEdge += texture2D( sTexture, vec2( vTextureCoord.x - x, vTextureCoord.y + y ) ) * 1.0;" +
			"    vertEdge += texture2D( sTexture, vec2( vTextureCoord.x    , vTextureCoord.y + y ) ) * 2.0;" +
			"    vertEdge += texture2D( sTexture, vec2( vTextureCoord.x + x, vTextureCoord.y + y ) ) * 1.0;" +
			"    vec3 edge = sqrt((horizEdge.rgb * horizEdge.rgb) + (vertEdge.rgb * vertEdge.rgb));" +
			"" +
			"    vec4 tempColor = vec4( edge, texture2D( sTexture, vTextureCoord ).a );" +
			"" +
			"    float grayScale = (tempColor.r * 0.299) + (tempColor.g * 0.587) + (tempColor.b *  0.114) ;" +
			"    vec4 grayScaleColor = vec4(grayScale,grayScale,grayScale,1.0);\n" +
			"    float binarized = smoothstep(0.4,0.4,grayScale);\n" +
			"    vec4 blackWhite = vec4(binarized,binarized,binarized,1);" +

			"        vec4 original = texture2D(sTexture, vTextureCoord);\n" +
			"    if( blackWhite.r == 1.0 )\n" +
			"    {\n" +
			"        gl_FragColor = vec4(0.0,1.0,0.0,1.0);\n" +
			"    }\n" +
			"    else\n" +
			"    {\n" +
			"        gl_FragColor = original;\n" +
			"    }" +
			"" +
			"}";

    public static final String[] FRAGMENT_SHADER_STRINGS = {
            SC_HEADER + SC_ORIGINAL,
            SC_HEADER + SC_HIGH_CONTRAST,
            SC_HEADER + SC_BLACK_ON_WHITE,
            SC_HEADER + SC_WHITE_ON_BLACK,

            SC_HEADER + SC_BLACK_ON_YELLOW,
            SC_HEADER + SC_YELLOW_ON_BLACK,

            SC_HEADER + SC_BLACK_ON_GREEN,
            SC_HEADER + SC_GREEN_ON_BLACK,

            SC_HEADER + SC_YELLOW_ON_BLUE,
            SC_HEADER + SC_BLUE_ON_YELLOW,

            SC_HEADER + SC_GRAYSCALE,
            SC_HEADER + SC_SOBEL,

    };
		
		
		public static int COLOR_LENGTH = FRAGMENT_SHADER_STRINGS.length;

}

