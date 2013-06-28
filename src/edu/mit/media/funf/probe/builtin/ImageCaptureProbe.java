/**
 * 
 * Funf: Open Sensing Framework
 * Copyright (C) 2010-2011 Nadav Aharony, Wei Pan, Alex Pentland.
 * Acknowledgments: Alan Gardner
 * Contact: nadav@media.mit.edu
 * 
 * Author(s): Pararth Shah (pararthshah717@gmail.com)
 * 
 * This file is part of Funf.
 * 
 * Funf is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Funf is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with Funf. If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package edu.mit.media.funf.probe.builtin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.google.gson.JsonObject;

import edu.mit.media.funf.Schedule;
import edu.mit.media.funf.config.Configurable;
import edu.mit.media.funf.probe.Probe.DisplayName;
import edu.mit.media.funf.probe.Probe.PassiveProbe;
import edu.mit.media.funf.probe.Probe.RequiredFeatures;
import edu.mit.media.funf.probe.Probe.RequiredPermissions;
import edu.mit.media.funf.probe.builtin.ProbeKeys.HighBandwidthKeys;
import edu.mit.media.funf.util.LogUtil;
import edu.mit.media.funf.util.NameGenerator;
import edu.mit.media.funf.util.NameGenerator.SystemUniqueTimestampNameGenerator;

@DisplayName("Image Capture Probe")
@RequiredPermissions({android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.CAMERA})
@RequiredFeatures("android.hardware.camera")
@Schedule.DefaultSchedule(interval=1800)
public class ImageCaptureProbe extends ImpulseProbe implements PassiveProbe, HighBandwidthKeys, SurfaceHolder.Callback {

	@Configurable
	private String fileNameBase = "imgcapturetest";
	
	@Configurable
	private String folderPath = Environment.getExternalStorageDirectory().getAbsolutePath();
		
	@Configurable
	private int selectedCamera = 1; // BACK_FACING = 0, FRONT_FACING = 1
	
	@Configurable
	private int jpegCompressionRatio = 90; // 0 - min size, 100 - max quality
	
	@Configurable
	private int cameraOpenDelay = 500; // allow delay for camera open in milliseconds
		
	private String mFileName;	
	private NameGenerator mNameGenerator;

	private Camera mCamera;
	private SurfaceView mSurfaceView;
    private SurfaceHolder mHolder;
    private Bitmap mBmp;
    private Parameters mParameters;
    private int mCameraAngle;
    
    private static final String CAMERA_PARAM_ORIENTATION = "orientation";
    private static final String CAMERA_PARAM_LANDSCAPE = "landscape";
    private static final String CAMERA_PARAM_PORTRAIT = "portrait";

    private WindowManager mWindowManager;
	private LayoutParams mParams;

	@Override
	protected void onEnable() {
		super.onEnable();
		mNameGenerator = new SystemUniqueTimestampNameGenerator(getContext());
	}
	
	@Override
	protected void onStart() {
		super.onStart();
        mFileName = folderPath + "/" + mNameGenerator.generateName(fileNameBase) + ".jpg";
        Log.d(LogUtil.TAG, "Image capture: start");
        
        mSurfaceView = new SurfaceView(getContext());
        mHolder = mSurfaceView.getHolder();
        mHolder.addCallback(this);
        
        mWindowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        mParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
        		WindowManager.LayoutParams.WRAP_CONTENT,
        		WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
        		WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        		PixelFormat.TRANSLUCENT);
        mWindowManager.addView(mSurfaceView, mParams);
        
        mSurfaceView.setZOrderOnTop(false);
        mHolder.setFormat(PixelFormat.TRANSPARENT);        
	}

	protected void onFinish() {
		try {
			File file = new File(mFileName);
			if (file.exists()) 
				file.delete();
			
			FileOutputStream out = new FileOutputStream(file);
			
			mBmp = rotate(mBmp, computePictureRotation(mCameraAngle, selectedCamera));
			mBmp.compress(Bitmap.CompressFormat.JPEG, jpegCompressionRatio, out);
			out.flush();
			out.close();
			
			JsonObject data = new JsonObject();
			data.addProperty(FILENAME, mFileName);
			sendData(data);
			mWindowManager.removeView(mSurfaceView);
			
			Log.d(LogUtil.TAG, "Image capture: finish");
			stop();
		} catch (Exception e) {
			e.printStackTrace();
			Log.e(LogUtil.TAG, "Image capture: error");
		}
	}
	
	@Override
	protected void onDisable() {
		super.onDisable();
		
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
		Log.d(LogUtil.TAG, "Image capture: surfaceChanged");
		mParameters = mCamera.getParameters();
		configureCameraParameters(mParameters);
		
		mCamera.startPreview();
		// to allow time for camera initiation on certain devices
		try {
			Thread.sleep(cameraOpenDelay);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		Camera.PictureCallback mCall = new Camera.PictureCallback()
		{
			@Override
			public void onPictureTaken(byte[] data, Camera camera)
			{
				Log.d(LogUtil.TAG, "Image capture: onPictureTaken");
				mBmp = BitmapFactory.decodeByteArray(data, 0, data.length);
				onFinish();
			}
		};

		mCamera.takePicture(null, null, mCall);
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		Log.d(LogUtil.TAG, "Image capture: surfaceCreated");
        safeCameraOpen();
        try {
           mCamera.setPreviewDisplay(mHolder);

        } catch (IOException exception) {
            mCamera.release();
            mCamera = null;
            Log.e(LogUtil.TAG, "Image capture: error");
        }
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		Log.d(LogUtil.TAG, "Image capture: surfaceDestroyed");
		mCamera.stopPreview();
		mCamera.release();
		mCamera = null;
	}
	
	private void safeCameraOpen() {
		mCamera = openSelectedCamera(selectedCamera);
		if (mCamera == null) {
			Log.e(LogUtil.TAG, "ImageCapture: failed to access camera");
			stop();
		}
	}
	
	@SuppressLint("NewApi")
	private Camera openSelectedCamera(int type) {
	    int cameraCount = 0;
	    Camera cam = null;
	    Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
	    cameraCount = Camera.getNumberOfCameras();
	    for ( int camIdx = 0; camIdx < cameraCount; camIdx++ ) {
	        Camera.getCameraInfo( camIdx, cameraInfo );
	        if (cameraInfo.facing == type) {
	            try {
	                cam = Camera.open( camIdx );
	            } catch (RuntimeException e) {
	                Log.e(LogUtil.TAG, "Camera failed to open: " + e.getLocalizedMessage());
	            }
	        }
	    }

	    return cam;
	}
		
	private void configureCameraParameters(Parameters cameraParams) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) { // for 2.1 and before
            if (isPortrait()) {
                cameraParams.set(CAMERA_PARAM_ORIENTATION, CAMERA_PARAM_PORTRAIT);
                mCameraAngle = 90;
            } else {
                cameraParams.set(CAMERA_PARAM_ORIENTATION, CAMERA_PARAM_LANDSCAPE);
                mCameraAngle = 0;
            }
        } else { // for 2.2 and later
            int rotation = mWindowManager.getDefaultDisplay().getRotation();
            switch (rotation) {
                case Surface.ROTATION_0: // This is display orientation
                    mCameraAngle = 90; // This is camera orientation
                    break;
                case Surface.ROTATION_90:
                    mCameraAngle = 0;
                    break;
                case Surface.ROTATION_180:
                    mCameraAngle = 270;
                    break;
                case Surface.ROTATION_270:
                    mCameraAngle = 180;
            		// image
                    break;
                default:
                    mCameraAngle = 90;
                    break;
            }
            Log.d(LogUtil.TAG, "angle: " + mCameraAngle);
            mCamera.setDisplayOrientation(mCameraAngle);
        }

        mCamera.setParameters(cameraParams);
    }

    public boolean isPortrait() {
        return (getContext().getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
    }
    
    public static int computePictureRotation(int cameraAngle, int cameraType) {
    	if (cameraType == 1 && (cameraAngle == 90 || cameraAngle == 270)) {
    		// for front-facing camera, portrait and reverse-portrait orientation 
    		// gives mirror image of actual picture, which needs to be corrected
    		return (180 + cameraAngle) % 360;
    	}
    	return cameraAngle;
    }
    
    private static Bitmap rotate(Bitmap bitmap, int degree) {
	    int w = bitmap.getWidth();
	    int h = bitmap.getHeight();

	    Matrix mtx = new Matrix();
	    mtx.setRotate(degree);

	    return Bitmap.createBitmap(bitmap, 0, 0, w, h, mtx, true);
	}
}
