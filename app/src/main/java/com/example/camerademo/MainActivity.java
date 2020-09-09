package com.example.camerademo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.view.GestureDetectorCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;


public class MainActivity extends AppCompatActivity implements GestureDetector.OnDoubleTapListener, GestureDetector.OnGestureListener {

    CameraManager mCameraManager;
    CameraCaptureSession captureSession;
    int maxHeight, maxWidth, displayRotation, mSensorOrientation, count, fwidth, fheight;
    static final int CAMERA_PERMISSION = 5, WRITE_PERMISSION=6, REQUEST_PERMISSION_SETTING=7;
    TextureView textureView;
    Button capture;
    Image image;
    CameraDevice mCameraDevice;
    CameraCharacteristics cameraCharacteristics;
    Size[] sizes;
    ImageReader mImageReader;
    static final String TAG="CameraErrors";
    boolean hasFlash;
    StreamConfigurationMap configs;
    String str, imagesDir, mCameraid;
    HandlerThread mBackgroundThread;
    Handler mBackgroundHandler;
    Size largest,mPreviewSize;
    MediaPlayer mp;
    CaptureRequest.Builder previewRequestBuilder, captureRequestBuilder;
    CaptureRequest previewRequest, captureRequest;
    SharedPreferences sp;
    GestureDetectorCompat gestureDetector;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static{
        ORIENTATIONS.append(Surface.ROTATION_0,90);
        ORIENTATIONS.append(Surface.ROTATION_90,0);
        ORIENTATIONS.append(Surface.ROTATION_180,270);
        ORIENTATIONS.append(Surface.ROTATION_270,180);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textureView = findViewById(R.id.TextureView);
        capture= findViewById(R.id.capture);
        View decorView = getWindow().getDecorView();
        // Hide the status bar.
        int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);
        hasFlash=getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        capture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takePicture();
            }
        });
        gestureDetector=new GestureDetectorCompat(this,this,mBackgroundHandler);
        gestureDetector.setOnDoubleTapListener(MainActivity.this);
        sp=getSharedPreferences(getPackageName(),MODE_PRIVATE);
        count=sp.getInt("Count",1);
    }

    @Override
    protected void onResume() {
        Log.i(TAG,"In onResume");
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            Log.i(TAG,"textureView available");
            if(ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            openCamera("0", textureView.getWidth(), textureView.getHeight());
        }
        else {
            Log.i(TAG,"textureView not available");
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @SuppressLint("MissingPermission")
    private void openCamera(String cameraId,int width,int height) {
        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            AskCameraPermission();
            return;
        }
        else{
            try {
                mCameraid = cameraId;
                cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraId);
                configs = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                sizes = configs.getOutputSizes(SurfaceTexture.class);
                largest = Collections.max(Arrays.asList(configs.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, 2);
                setUpCameraOutput(width, height);
                configureTransform(width, height);
                mCameraManager.openCamera(cameraId, stateCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private void setUpCameraOutput(int width, int height) {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            maxHeight= displayMetrics.heightPixels;
            maxWidth= displayMetrics.widthPixels;
        getDisplayRotation();
        mSensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            boolean swapRotation=false;
            switch(displayRotation){
                case Surface.ROTATION_0:
                case Surface.ROTATION_180:
                    if(mSensorOrientation == 90 || mSensorOrientation == 270)
                        swapRotation=true;
                    break;
                case Surface.ROTATION_90:
                case Surface.ROTATION_270:
                    if(mSensorOrientation==0||mSensorOrientation==180)
                        swapRotation=true;
                    break;
                default:
                    Log.e(TAG,"Invalid display rotation");
            }
            int rotatedWidth, rotatedHeight;
            rotatedHeight=height;
            rotatedWidth=width;
            if(swapRotation){
                rotatedWidth=height;
                rotatedHeight=width;
                maxWidth=displayMetrics.heightPixels;
                maxHeight=displayMetrics.widthPixels;
            }
            if(maxWidth>1920)
                maxWidth=1920;
            if(maxHeight>1080)
                maxHeight= 1080;
            mPreviewSize=chooseOptimalSize(largest,sizes,maxHeight,maxWidth,rotatedHeight,rotatedWidth);

    }

    private void AskCameraPermission() {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                Snackbar.make(findViewById(android.R.id.content), R.string.allowpermission, Snackbar.LENGTH_INDEFINITE).setAction(R.string.ok, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
                    }
                }).show();
            }
            else
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    }

    private void AskStoragePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            Snackbar.make(findViewById(android.R.id.content), "Allow permission to save images", Snackbar.LENGTH_INDEFINITE).setAction("OK", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERMISSION);
                }
            }).show();
        } else
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == CAMERA_PERMISSION) {
            if(!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)){
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                Toast.makeText(this,"Enable camera permission",Toast.LENGTH_LONG).show();
                startActivityForResult(intent, REQUEST_PERMISSION_SETTING);
            }
            else if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Log.i(TAG, "Camera Permission denied");
                finish();
            }
            else
                openCamera("0",fwidth,fheight);
        }
        else
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode==WRITE_PERMISSION){
            if(grantResults.length>0 && grantResults[0]==PackageManager.PERMISSION_GRANTED)
                capture.setEnabled(true);
        }
        else
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            mCameraDevice = cameraDevice;
            startPreview();
            if(ActivityCompat.checkSelfPermission(MainActivity.this,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
                capture.setEnabled(false);
                AskStoragePermission();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            closeCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            closeCamera();
        }
    };

    private void closeCamera() {
        if(mCameraDevice!=null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
        if (null != captureSession) {
            captureSession.close();
            captureSession = null;
        }

    }

    TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            fheight=i1;
            fwidth=i;
            openCamera("0",i,i1);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
            configureTransform(i,i1);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

        }
    };

    void startPreview(){
        SurfaceTexture texture=textureView.getSurfaceTexture();
        assert texture != null;
        texture.setDefaultBufferSize(mPreviewSize.getWidth(),mPreviewSize.getHeight());
        Surface surface=new Surface(texture);
        try {
            previewRequestBuilder =mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        previewRequestBuilder.addTarget(surface);
        OutputConfiguration output=new OutputConfiguration(surface);
        OutputConfiguration output2=new OutputConfiguration(mImageReader.getSurface());
        List<OutputConfiguration> outputs=new ArrayList<>();
        outputs.add(output);
        outputs.add(output2);
        SessionConfiguration config=new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, new Executor() {
            @Override
            public void execute(Runnable runnable) {
                new Thread(runnable).start();
            }
        },captureStateCallback);
        try {
            mCameraDevice.createCaptureSession(config);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        Log.i(TAG,"In ConfigureTransform");
        if (null == textureView || null == mPreviewSize) {
            return;
        }
        getDisplayRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == displayRotation || Surface.ROTATION_270 == displayRotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (displayRotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == displayRotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    void takePicture(){
        if(null == mCameraDevice) {
            Log.e(TAG, "CameraDevice is null");
            return;
        }
        try {
            captureRequestBuilder=mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        captureRequestBuilder.addTarget(mImageReader.getSurface());
        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        setAutoFlash(captureRequestBuilder);
        getDisplayRotation();
        captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,getOrientation(displayRotation));
        CameraCaptureSession.CaptureCallback captureCallback=new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                super.onCaptureCompleted(session, request, result);
                try {
                    captureSession.setRepeatingRequest(previewRequest,null, mBackgroundHandler);
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                super.onCaptureStarted(session, request, timestamp, frameNumber);
                SharedPreferences.Editor editor=sp.edit();
                editor.putInt("Count",count);
                count++;
                editor.apply();
                mp=MediaPlayer.create(MainActivity.this,R.raw.camera_shutter_click_01);
                mp.setOnCompletionListener(completionListener);
                mp.start();
            }
        };
        mImageReader.setOnImageAvailableListener(readerListener,mBackgroundHandler);
        captureRequest=captureRequestBuilder.build();
        try {
            captureSession.capture(captureRequest,captureCallback,mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void getDisplayRotation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            displayRotation = getDisplay().getRotation();
        else
            displayRotation = getWindowManager().getDefaultDisplay().getRotation();
    }

    private int getOrientation(int rotation) {
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }


    private Size chooseOptimalSize(Size largest, Size[] choices, int max_height, int max_width, int height, int width) {
        List<Size> bigEnough=new ArrayList<>();
        List<Size> notBigEnough=new ArrayList<>();
        int w=largest.getWidth();
        int h=largest.getHeight();
        for(Size option:choices){
            if(option.getWidth()<=max_width && option.getHeight()<=max_height && option.getHeight()==option.getWidth()*h/w){
                if(option.getWidth()>=width && option.getHeight()>=height)
                    bigEnough.add(option);
                else
                    notBigEnough.add(option);
            }
        }
        if(bigEnough.size()>0)
            return Collections.min(bigEnough,new CompareSizesByArea());
        else if(notBigEnough.size()>0)
            return Collections.max(notBigEnough,new CompareSizesByArea());
        else {
            Log.e(TAG, "No suitable size available");
            return choices[0];
        }
    }

    void startBackgroundThread(){
        mBackgroundThread=new HandlerThread("background thread");
        mBackgroundThread.start();
        mBackgroundHandler=new Handler(mBackgroundThread.getLooper());
    }

    void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    CameraCaptureSession.StateCallback captureStateCallback=new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            if(null==mCameraDevice)
                return;
            captureSession=cameraCaptureSession;
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(previewRequestBuilder);
            previewRequest=previewRequestBuilder.build();
            try {
                captureSession.setRepeatingRequest(previewRequest,null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Toast.makeText(MainActivity.this, R.string.Failed,Toast.LENGTH_SHORT).show();
        }
    };

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (hasFlash) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    ImageReader.OnImageAvailableListener readerListener;

    {
        readerListener = new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                image = reader.acquireNextImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                mBackgroundHandler.post(new ImageSaver(bytes));
            }
        };
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
        return false;
    }

    @Override
    public boolean onDoubleTap(MotionEvent motionEvent) {
        closeCamera();
        if(mCameraid.equals("0"))
            openCamera("1",fwidth,fheight);
        else
            openCamera("0",fwidth,fheight);
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent motionEvent) {
        return false;
    }

    @Override
    public boolean onDown(MotionEvent motionEvent) {
        return false;
    }

    @Override
    public void onShowPress(MotionEvent motionEvent) {

    }

    @Override
    public boolean onSingleTapUp(MotionEvent motionEvent) {
        return false;
    }

    @Override
    public boolean onScroll(MotionEvent motionEvent, MotionEvent motionEvent1, float v, float v1) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent motionEvent) {

    }

    @Override
    public boolean onFling(MotionEvent motionEvent, MotionEvent motionEvent1, float v, float v1) {
        return false;
    }

    class ImageSaver implements Runnable {
        byte[] bytes;

        ImageSaver(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public void run() {
            Log.i(TAG, "In ImageSaver");
            OutputStream output = null;
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues cv = new ContentValues();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
                str = sdf.format(new Date());
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG-" + str + "-" + count+".jpg");
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES+File.separator+"Camera Demo");
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                try {
                    output = resolver.openOutputStream(Objects.requireNonNull(uri));
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            } else {
                imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString();
                File image = new File(imagesDir, "IMG-" + str + "-" + count+".jpg");
                try {
                    output = new FileOutputStream(image);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
            try {
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                image.close();
                try {
                    output.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            Toast.makeText(MainActivity.this,"Image Saved",Toast.LENGTH_SHORT).show();
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long)lhs.getWidth()*lhs.getHeight()-(long)rhs.getWidth()*rhs.getHeight());
        }
    }

    MediaPlayer.OnCompletionListener completionListener= new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mediaPlayer) {
            mp.release();
            Log.i(TAG,"MediaPlayer released!");
        }
    };
}