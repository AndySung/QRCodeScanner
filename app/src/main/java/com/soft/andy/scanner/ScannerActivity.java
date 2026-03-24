package com.soft.andy.scanner;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;

public class ScannerActivity extends AppCompatActivity {

    private static final String TAG = "QRScanner";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int GALLERY_PERMISSION_REQUEST = 101;

    private PreviewView previewView;
    private View scanFrame;
    private View focusIndicator;
    private FrameLayout rootLayout;
    private Button galleryButton;
    private Button captureButton;
    private Button flashButton;
    private LinearLayout resultContainer;
    private ImageView previewImage;
    private Button retryButton;
    private Button recognizeButton;
    private Switch saveSwitch;
    private Switch autoCaptureSwitch;
    private Switch manualExposureSwitch;
    private boolean autoCaptureEnabled = true; // 自动拍照开关，默认打开
    private boolean manualExposureEnabled = false; // 手动曝光开关，默认关闭
    
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private boolean isScanning = true;
    private boolean isFlashOn = false;
    private boolean saveToGallery = false;
    private Bitmap currentBitmap;

    // 自动拍照相关
    private Handler autoCaptureHandler;
    private Runnable autoCaptureRunnable;
    private boolean autoCaptureStarted = false;
    private boolean isViewingResult = false; // 是否正在查看结果
    private long lastDetectionTime = 0; // 最后一次检测到二维码的时间
    private static final long AUTO_CAPTURE_DELAY = 3000; // 3秒无识别则自动拍照
    private TextView countdownText;
    private Spinner zoomSpinner;
    private int currentZoomLevel = 4; // 默认放大3倍
    private static final int MIN_ZOOM = 2;
    private static final int MAX_ZOOM = 7; // 最大放大7倍

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupBarcodeScanner();
        checkCameraPermission();

        cameraExecutor = Executors.newSingleThreadExecutor();
        autoCaptureHandler = new Handler(Looper.getMainLooper());

        // 设置点击对焦监听器（在根布局上设置，确保能捕获点击事件）
        setupFocusListener();
    }

    private void initViews() {
        rootLayout = findViewById(android.R.id.content);
        previewView = findViewById(R.id.previewView);
        scanFrame = findViewById(R.id.scanFrame);
        focusIndicator = findViewById(R.id.focusIndicator);
        galleryButton = findViewById(R.id.galleryButton);
        captureButton = findViewById(R.id.captureButton);
        flashButton = findViewById(R.id.flashButton);
        resultContainer = findViewById(R.id.resultContainer);
        previewImage = findViewById(R.id.previewImage);
        retryButton = findViewById(R.id.retryButton);
        recognizeButton = findViewById(R.id.recognizeButton);
        saveSwitch = findViewById(R.id.saveSwitch);
        autoCaptureSwitch = findViewById(R.id.autoCaptureSwitch);
        manualExposureSwitch = findViewById(R.id.manualExposureSwitch);
        countdownText = findViewById(R.id.countdownText);
        zoomSpinner = findViewById(R.id.zoomSpinner);

        // 初始化放大倍数选择器
        String[] zoomLevels = new String[MAX_ZOOM - MIN_ZOOM + 1];
        for (int i = MIN_ZOOM; i <= MAX_ZOOM; i++) {
            zoomLevels[i - MIN_ZOOM] = i + "倍";
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, zoomLevels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(Color.WHITE);
                }
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(Color.WHITE);
                    view.setBackgroundColor(Color.parseColor("#666666"));
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        zoomSpinner.setAdapter(adapter);
        zoomSpinner.setSelection(currentZoomLevel - MIN_ZOOM);
        zoomSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentZoomLevel = position + MIN_ZOOM;
                Toast.makeText(ScannerActivity.this, "已选择 " + currentZoomLevel + " 倍放大", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        galleryButton.setOnClickListener(v -> pickImageFromGallery());
        captureButton.setOnClickListener(v -> takePicture());
        flashButton.setOnClickListener(v -> toggleFlash());
        retryButton.setOnClickListener(v -> retryCapture());
        recognizeButton.setOnClickListener(v -> recognizeImage());
        
        saveSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveToGallery = isChecked;
            Toast.makeText(this, isChecked ? "已开启保存到相册" : "已关闭保存到相册", Toast.LENGTH_SHORT).show();
        });

        autoCaptureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoCaptureEnabled = isChecked;
            Toast.makeText(this, isChecked ? "已开启自动拍照" : "已关闭自动拍照", Toast.LENGTH_SHORT).show();
        });

        manualExposureSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            manualExposureEnabled = isChecked;
            Toast.makeText(this, isChecked ? "已开启手动曝光" : "已关闭手动曝光", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupBarcodeScanner() {
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        
        barcodeScanner = BarcodeScanning.getClient(options);
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        } else {
            startCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraPreview();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "相机初始化失败", e);
                Toast.makeText(this, "相机初始化失败", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraPreview() {
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        // 获取屏幕尺寸
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = displayMetrics.widthPixels;
        int screenHeight = displayMetrics.heightPixels;

        // Preview 设置合适的预览分辨率
        Preview preview = new Preview.Builder()
                .setTargetResolution(new Size(screenWidth, screenHeight)) // 使用屏幕分辨率
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // ImageCapture 设置高分辨率
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetResolution(new Size(3280, 2160)) // 高分辨率拍照
                .build();

        // ImageAnalysis 用较高分辨率来检测小二维码
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(1920, 1080)) // 1080p 用于检测小二维码
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(
                    (LifecycleOwner) this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
            );
            // 定焦设备不自动对焦/曝光
        } catch (Exception e) {
            Log.e(TAG, "相机绑定失败", e);
        }
    }

    /**
     * 设置点击对焦监听器
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupFocusListener() {
        if (rootLayout == null) {
            return;
        }
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                // 只有在手动曝光开关打开时才触发曝光调整
                if (!manualExposureEnabled) {
                    return false;
                }

                // 检查是否点击在按钮或开关等控件上，如果是则不处理曝光
                int x = (int) event.getRawX();
                int y = (int) event.getRawY();
                Log.d(TAG, "点击事件触发: rawX=" + x + ", rawY=" + y);

                // 获取点击位置相对于previewView的坐标
                int[] previewLocation = new int[2];
                previewView.getLocationOnScreen(previewLocation);
                float previewX = event.getRawX() - previewLocation[0];
                float previewY = event.getRawY() - previewLocation[1];

                Log.d(TAG, "预览区域坐标: previewX=" + previewX + ", previewY=" + previewY);

                // 点击屏幕调整曝光
                focusOnPoint(previewX, previewY);
                return true;
            }
            return false;
        });
    }

    /**
     * 在指定位置调整曝光（定焦设备不支持对焦，只支持曝光调整）
     */
    private void focusOnPoint(float x, float y) {
        if (camera == null) {
            return;
        }

        // 创建测光点的MeteringPoint
        MeteringPointFactory meteringPointFactory = new SurfaceOrientedMeteringPointFactory(
                previewView.getWidth(), previewView.getHeight());
        MeteringPoint point = meteringPointFactory.createPoint(x, y);

        // 只开启自动曝光（FLAG_AE），不开启对焦（FLAG_AF），因为是定焦设备
        FocusMeteringAction action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build();

        // 开始曝光调整
        camera.getCameraControl().startFocusAndMetering(action);
        Log.d(TAG, "曝光调整位置: (" + x + ", " + y + "), previewView尺寸: " + previewView.getWidth() + "x" + previewView.getHeight());

        // 显示曝光调整提示
        showFocusIndicator(x, y);
    }

    /**
     * 在屏幕中央对焦（扫描框位置）
     */
    private void focusOnCenter() {
        if (camera == null) {
            return;
        }
        float centerX = previewView.getWidth() / 2f;
        float centerY = previewView.getHeight() / 2f;
        focusOnPoint(centerX, centerY);
    }

    /**
     * 显示对焦指示器动画
     */
    private void showFocusIndicator(float x, float y) {
        if (focusIndicator == null) {
            return;
        }

        // 获取对焦指示器的大小
        int indicatorSize = 80;
        int halfSize = indicatorSize / 2;

        // 设置位置（让中心对准点击位置）
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) focusIndicator.getLayoutParams();
        params.leftMargin = (int) (x - halfSize);
        params.topMargin = (int) (y - halfSize);
        focusIndicator.setLayoutParams(params);

        // 显示指示器
        focusIndicator.setVisibility(View.VISIBLE);
        focusIndicator.setAlpha(1f);
        focusIndicator.setScaleX(1f);
        focusIndicator.setScaleY(1f);

        // 创建缩放动画（先放大再缩小）
        focusIndicator.animate()
                .scaleX(1.5f)
                .scaleY(1.5f)
                .alpha(0f)
                .setDuration(600)
                .withEndAction(() -> {
                    focusIndicator.setVisibility(View.GONE);
                })
                .start();
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(ImageProxy imageProxy) {
        if (!isScanning || imageProxy == null || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        final int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(),
                rotationDegrees
        );

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty() && isScanning) {
                        for (Barcode barcode : barcodes) {
                            String result = barcode.getRawValue();
                            if (result != null && !result.isEmpty()) {
                                // 成功识别到二维码
                                cancelAutoCapture();
                                isScanning = false;
                                autoCaptureStarted = true;
                                lastDetectionTime = 0; // 重置计时
                                runOnUiThread(() -> showResultAlert(result));
                                return;
                            }
                        }

                        // 检测到了二维码但没识别出来，更新检测时间
                        if (lastDetectionTime == 0) {
                            lastDetectionTime = System.currentTimeMillis();
                            Log.d(TAG, "检测到二维码但未识别，开始计时...");
                        }
                    } else {
                        // 没有检测到二维码，也开始计时（可能是小二维码）
                        if (lastDetectionTime == 0) {
                            lastDetectionTime = System.currentTimeMillis();
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    // 识别失败，也开始计时
                    if (lastDetectionTime == 0) {
                        lastDetectionTime = System.currentTimeMillis();
                    }
                })
                .addOnCompleteListener(task -> {
                    // 检查是否需要启动自动拍照（需要开关打开）
                    // 如果距离开始检测已经超过3秒，且没有识别出来
                    if (autoCaptureEnabled && isScanning && !autoCaptureStarted && !isViewingResult
                            && lastDetectionTime > 0
                            && (System.currentTimeMillis() - lastDetectionTime) >= AUTO_CAPTURE_DELAY) {
                        Log.d(TAG, "3秒未识别，触发自动拍照");
                        lastDetectionTime = 0; // 重置，防止重复触发
                        startAutoCapture();
                    }
                    imageProxy.close();
                });
    }

    /**
     * 启动自动拍照（不显示倒计时，直接拍照）
     */
    private void startAutoCapture() {
        if (autoCaptureStarted) {
            return;
        }
        autoCaptureStarted = true;

        runOnUiThread(() -> {
            countdownText.setVisibility(View.VISIBLE);
            countdownText.setText("正在拍照...");
            captureButton.setEnabled(false);
            captureButton.setText("自动拍照中...");
            resetButton();
        });

        // 延迟 500 毫秒后拍照，让用户准备好
        autoCaptureHandler.postDelayed(() -> {
            takePictureForAutoCapture();
        }, 500);
    }

    /**
     * 自动拍照完成后的处理
     */
    private void takePictureForAutoCapture() {
        if (camera == null || imageCapture == null) {
            cancelAutoCapture();
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        // 防止内存泄漏，先回收之前的 Bitmap
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }

        File photoFile = new File(getCacheDir(), "qr_capture.jpg");
        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor,
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                    try {
                        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                        photoFile.delete();

                        if (bitmap == null) {
                            runOnUiThread(() -> {
                                cancelAutoCapture();
                                Toast.makeText(ScannerActivity.this, "图片读取失败", Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }

                        int imgWidth = bitmap.getWidth();
                        int imgHeight = bitmap.getHeight();

                        // 取中间 1/3 区域
                        int cropSize = Math.min(imgWidth, imgHeight) / 3;
                        int cropLeft = (imgWidth - cropSize) / 2;
                        int cropTop = (imgHeight - cropSize) / 2;

                        // 裁剪中间区域
                        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropSize, cropSize);
                        bitmap.recycle();

                        // 放大 N 倍
                        currentBitmap = Bitmap.createScaledBitmap(croppedBitmap, cropSize * currentZoomLevel, cropSize * currentZoomLevel, true);
                        croppedBitmap.recycle();

                        // 显示图片
                        runOnUiThread(() -> {
                            previewImage.setImageBitmap(currentBitmap);
                            resultContainer.setVisibility(View.VISIBLE);
                            countdownText.setVisibility(View.GONE);
                            // 自动识别
                            recognizeImageForAutoCapture();
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "自动拍照处理失败", e);
                        runOnUiThread(() -> {
                            cancelAutoCapture();
                            Toast.makeText(ScannerActivity.this, "处理失败", Toast.LENGTH_SHORT).show();
                        });
                    }
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    runOnUiThread(() -> {
                        cancelAutoCapture();
                        Toast.makeText(ScannerActivity.this, "拍照失败", Toast.LENGTH_SHORT).show();
                    });
                }
            });
    }

    /**
     * 自动拍照后的识别（识别失败时不自动重试，等待用户点击重试）
     */
    private void recognizeImageForAutoCapture() {
        if (currentBitmap == null) {
            cancelAutoCapture();
            return;
        }

        InputImage inputImage = InputImage.fromBitmap(currentBitmap, 0);
        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty()) {
                        String result = barcodes.get(0).getRawValue();
                        runOnUiThread(() -> {
                            if (result != null && !result.isEmpty()) {
                                // 识别成功，停止扫描并显示结果
                                isScanning = false;
                                // 标记自动拍照已完成，用户需要点击重拍后才能再次自动拍照
                                autoCaptureStarted = true;
                                showResultAlert(result);
                                resultContainer.setVisibility(View.GONE);
                                currentBitmap.recycle();
                                currentBitmap = null;
                            } else {
                                // 识别失败，保持状态，用户需要点击重拍
                                autoCaptureStarted = true;
                                cancelAutoCapture();
                                isViewingResult = false; // 用户可以查看图片并重试
                                Toast.makeText(ScannerActivity.this, "未识别到有效二维码，请重试", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        // 识别失败，保持状态，用户需要点击重拍
                        runOnUiThread(() -> {
                            autoCaptureStarted = true;
                            cancelAutoCapture();
                            isViewingResult = false; // 用户可以查看图片并重试
                            Toast.makeText(ScannerActivity.this, "未识别到二维码，请重试", Toast.LENGTH_SHORT).show();
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    runOnUiThread(() -> {
                        autoCaptureStarted = true;
                        cancelAutoCapture();
                        isViewingResult = false; // 用户可以查看图片并重试
                        Toast.makeText(ScannerActivity.this, "识别失败，请重试", Toast.LENGTH_SHORT).show();
                    });
                });
    }

    /**
     * 取消自动拍照倒计时
     */
    private void cancelAutoCapture() {
        if (autoCaptureRunnable != null) {
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable);
            autoCaptureRunnable = null;
        }
        runOnUiThread(() -> {
            countdownText.setVisibility(View.GONE);
            if (resultContainer.getVisibility() != View.VISIBLE) {
                captureButton.setEnabled(true);
                captureButton.setText("拍照");
            }
        });
        // 注意：这里不设置 autoCaptureStarted = false
        // 只有用户点击"重拍"后才允许再次自动拍照
    }

    /**
     * 重置自动拍照状态
     */
    private void resetAutoCaptureState() {
        autoCaptureStarted = false;
    }

    private void takePicture() {
        if (camera == null || imageCapture == null) {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show();
            return;
        }

        captureButton.setText("拍摄中...");
        captureButton.setEnabled(false);

        File photoFile = new File(getCacheDir(), "qr_capture.jpg");

        ImageCapture.OutputFileOptions outputOptions = 
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, 
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                    try {
                        // 如果开启了保存到相册
                        if (saveToGallery) {
                            saveImageToGallery(photoFile);
                        }
                        
                        Bitmap bitmap = BitmapFactory.decodeFile(photoFile.getAbsolutePath());
                        photoFile.delete();
                        
                        if (bitmap == null) {
                            runOnUiThread(() -> {
                                Toast.makeText(ScannerActivity.this, "图片读取失败", Toast.LENGTH_SHORT).show();
                                resetButton();
                            });
                            return;
                        }
                        
                        int imgWidth = bitmap.getWidth();
                        int imgHeight = bitmap.getHeight();
                        
                        // 取中间 1/3 区域
                        int cropSize = Math.min(imgWidth, imgHeight) / 3;
                        int cropLeft = (imgWidth - cropSize) / 2;
                        int cropTop = (imgHeight - cropSize) / 2;
                        
                        Log.d(TAG, "原图: " + imgWidth + "x" + imgHeight + ", 裁剪: " + cropSize + "x" + cropSize);
                        
                        // 裁剪中间区域
                        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropSize, cropSize);
                        bitmap.recycle();
                        
                        // 放大 N 倍
                        currentBitmap = Bitmap.createScaledBitmap(croppedBitmap, cropSize * currentZoomLevel, cropSize * currentZoomLevel, true);
                        croppedBitmap.recycle();
                        
                        Log.d(TAG, "放大后: " + currentBitmap.getWidth() + "x" + currentBitmap.getHeight());
                        
                        // 显示图片
                        runOnUiThread(() -> {
                            previewImage.setImageBitmap(currentBitmap);
                            resultContainer.setVisibility(View.VISIBLE);
                            captureButton.setText("已拍照");
                            resetButton();
                        });
                                
                    } catch (Exception e) {
                        Log.e(TAG, "处理失败", e);
                        runOnUiThread(() -> {
                            Toast.makeText(ScannerActivity.this, "处理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            resetButton();
                        });
                    }
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    runOnUiThread(() -> {
                        Toast.makeText(ScannerActivity.this, "拍照失败: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                        resetButton();
                    });
                }
            });
    }
    
    private void saveImageToGallery(File file) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String filename = "QR_" + timestamp + ".jpg";
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QRScanner");
                    
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri != null) {
                        OutputStream out = getContentResolver().openOutputStream(uri);
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                        out.close();
                        Log.d(TAG, "已保存到相册: " + filename);
                    }
                } else {
                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    File qrDir = new File(picturesDir, "QRScanner");
                    if (!qrDir.exists()) qrDir.mkdirs();
                    File destFile = new File(qrDir, filename);
                    FileOutputStream out = new FileOutputStream(destFile);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                    out.close();
                    Log.d(TAG, "已保存到相册: " + destFile.getAbsolutePath());
                }
                bitmap.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "保存相册失败", e);
        }
    }

    private void retryCapture() {
        resultContainer.setVisibility(View.GONE);
        isViewingResult = false;
        lastDetectionTime = 0; // 重置检测时间
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        captureButton.setText("拍照");
        captureButton.setEnabled(true);
        // 重置自动拍照状态
        resetAutoCaptureState();
        bindCameraPreview();
    }
    
    private void resetButton() {
        captureButton.setText("拍照");
        captureButton.setEnabled(true);
    }
    
    private void recognizeImage() {
        if (currentBitmap == null) {
            Toast.makeText(this, "没有图片", Toast.LENGTH_SHORT).show();
            return;
        }
        
        recognizeButton.setEnabled(false);
        recognizeButton.setText("识别中...");
        
        InputImage inputImage = InputImage.fromBitmap(currentBitmap, 0);
        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty()) {
                        String result = barcodes.get(0).getRawValue();
                        runOnUiThread(() -> {
                            if (result != null && !result.isEmpty()) {
                                showResultAlert(result);
                                resultContainer.setVisibility(View.GONE);
                                currentBitmap.recycle();
                                currentBitmap = null;
                            } else {
                                Toast.makeText(ScannerActivity.this, "未识别到有效二维码", Toast.LENGTH_SHORT).show();
                            }
                            recognizeButton.setEnabled(true);
                            recognizeButton.setText("识别二维码");
                        });
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(ScannerActivity.this, "未识别到二维码", Toast.LENGTH_SHORT).show();
                            recognizeButton.setEnabled(true);
                            recognizeButton.setText("识别二维码");
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    runOnUiThread(() -> {
                        Toast.makeText(ScannerActivity.this, "识别失败", Toast.LENGTH_SHORT).show();
                        recognizeButton.setEnabled(true);
                        recognizeButton.setText("识别二维码");
                    });
                });
    }

    private void toggleFlash() {
        if (camera != null) {
            isFlashOn = !isFlashOn;
            camera.getCameraControl().enableTorch(isFlashOn);
            flashButton.setText(isFlashOn ? "关闭" : "手电筒");
        }
    }

    private void pickImageFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, GALLERY_PERMISSION_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GALLERY_PERMISSION_REQUEST && resultCode == RESULT_OK && data != null) {
            try {
                Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(data.getData()));
                if (bitmap != null) {
                    int imgWidth = bitmap.getWidth();
                    int imgHeight = bitmap.getHeight();
                    
                    // 取中间 1/3 区域
                    int cropSize = Math.min(imgWidth, imgHeight) / 3;
                    int cropLeft = (imgWidth - cropSize) / 2;
                    int cropTop = (imgHeight - cropSize) / 2;
                    
                    Log.d(TAG, "相册图片: " + imgWidth + "x" + imgHeight + ", 裁剪: " + cropSize + "x" + cropSize);
                    
                    // 裁剪中间区域
                    Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropSize, cropSize);
                    bitmap.recycle();
                    
                    // 放大 3 倍
                    currentBitmap = Bitmap.createScaledBitmap(croppedBitmap, cropSize * currentZoomLevel, cropSize * currentZoomLevel, true);
                    croppedBitmap.recycle();
                    
                    // 显示图片
                    previewImage.setImageBitmap(currentBitmap);
                    resultContainer.setVisibility(View.VISIBLE);
                    captureButton.setText("已选择");
                    captureButton.setEnabled(false);
                    resetButton();
                    // 自动识别
                    recognizeImage();
                }
            } catch (Exception e) {
                Log.e(TAG, "解析失败", e);
            }
        }
    }

    private void showResultAlert(String message) {
        isViewingResult = true;
        new AlertDialog.Builder(this)
                .setTitle("扫描结果")
                .setMessage(message)
                .setPositiveButton("确定", (dialog, which) -> {
                    isViewingResult = false;
                    // 如果自动拍照开关打开，则触发自动拍照
                    if (autoCaptureEnabled) {
                        startAutoCaptureOnResultConfirmed();
                    }
                    // 不重置 autoCaptureStarted，只有点击"重拍"后才允许再次自动拍照
                    new Handler(Looper.getMainLooper()).postDelayed(() -> isScanning = true, 1500);
                })
                .setNegativeButton("复制", (dialog, which) -> {
                    isViewingResult = false;
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("QR", message);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                    // 如果自动拍照开关打开，则触发自动拍照
                    if (autoCaptureEnabled) {
                        startAutoCaptureOnResultConfirmed();
                    }
                    // 不重置 autoCaptureStarted，只有点击"重拍"后才允许再次自动拍照
                    new Handler(Looper.getMainLooper()).postDelayed(() -> isScanning = true, 1500);
                })
                .setCancelable(false)
                .show();
    }

    /**
     * 在确认结果后触发自动拍照（用于点击确定/复制后继续扫描）
     * 只有在自动拍照开关打开时才会触发
     */
    private void startAutoCaptureOnResultConfirmed() {
        // 只有开关打开时才触发自动拍照
        if (!autoCaptureEnabled) {
            return;
        }
        // 先重置自动拍照状态，允许再次自动拍照
        resetAutoCaptureState();
        // 设置检测时间，触发自动拍照流程
        lastDetectionTime = System.currentTimeMillis();
        // 重新绑定相机预览
        bindCameraPreview();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isScanning = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (barcodeScanner != null) barcodeScanner.close();
        if (autoCaptureHandler != null && autoCaptureRunnable != null) {
            autoCaptureHandler.removeCallbacks(autoCaptureRunnable);
        }
    }
}
