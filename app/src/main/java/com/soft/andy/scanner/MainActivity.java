package com.soft.andy.scanner;

import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
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

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "QRScanner";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final int GALLERY_PERMISSION_REQUEST = 101;

    private PreviewView previewView;
    private View scanFrame;
    private Button galleryButton;
    private Button captureButton;
    private Button flashButton;
    private LinearLayout resultContainer;
    private ImageView previewImage;
    private Button retryButton;
    private Button recognizeButton;
    private Switch saveSwitch;
    
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private Camera camera;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private boolean isScanning = true;
    private boolean isFlashOn = false;
    private boolean saveToGallery = false;
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupBarcodeScanner();
        checkCameraPermission();

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        scanFrame = findViewById(R.id.scanFrame);
        galleryButton = findViewById(R.id.galleryButton);
        captureButton = findViewById(R.id.captureButton);
        flashButton = findViewById(R.id.flashButton);
        resultContainer = findViewById(R.id.resultContainer);
        previewImage = findViewById(R.id.previewImage);
        retryButton = findViewById(R.id.retryButton);
        recognizeButton = findViewById(R.id.recognizeButton);
        saveSwitch = findViewById(R.id.saveSwitch);
        
        galleryButton.setOnClickListener(v -> pickImageFromGallery());
        captureButton.setOnClickListener(v -> takePicture());
        flashButton.setOnClickListener(v -> toggleFlash());
        retryButton.setOnClickListener(v -> retryCapture());
        recognizeButton.setOnClickListener(v -> recognizeImage());
        
        saveSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveToGallery = isChecked;
            Toast.makeText(this, isChecked ? "已开启保存到相册" : "已关闭保存到相册", Toast.LENGTH_SHORT).show();
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

        // ImageAnalysis 用较低分辨率（用于二维码实时扫描）
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(new Size(1920, 1080)) // 720p 足够二维码识别
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
        } catch (Exception e) {
            Log.e(TAG, "相机绑定失败", e);
        }
    }

    private void analyzeImage(ImageProxy imageProxy) {
        if (!isScanning || imageProxy == null || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage inputImage = InputImage.fromMediaImage(
                imageProxy.getImage(),
                imageProxy.getImageInfo().getRotationDegrees()
        );

        barcodeScanner.process(inputImage)
                .addOnSuccessListener(barcodes -> {
                    if (!barcodes.isEmpty() && isScanning) {
                        Barcode barcode = barcodes.get(0);
                        String result = barcode.getRawValue();
                        if (result != null && !result.isEmpty()) {
                            isScanning = false;
                            runOnUiThread(() -> showResultAlert(result));
                        }
                    }
                })
                .addOnFailureListener(e -> {})
                .addOnCompleteListener(task -> imageProxy.close());
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
                                Toast.makeText(MainActivity.this, "图片读取失败", Toast.LENGTH_SHORT).show();
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
                        
                        // 放大 3 倍
                        currentBitmap = Bitmap.createScaledBitmap(croppedBitmap, cropSize * 3, cropSize * 3, true);
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
                            Toast.makeText(MainActivity.this, "处理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            resetButton();
                        });
                    }
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "拍照失败: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
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
        if (currentBitmap != null) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        captureButton.setText("拍照");
        captureButton.setEnabled(true);
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
                                Toast.makeText(MainActivity.this, "未识别到有效二维码", Toast.LENGTH_SHORT).show();
                            }
                            recognizeButton.setEnabled(true);
                            recognizeButton.setText("识别二维码");
                        });
                    } else {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "未识别到二维码", Toast.LENGTH_SHORT).show();
                            recognizeButton.setEnabled(true);
                            recognizeButton.setText("识别二维码");
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "识别失败", Toast.LENGTH_SHORT).show();
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
                    currentBitmap = Bitmap.createScaledBitmap(croppedBitmap, cropSize * 3, cropSize * 3, true);
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
        new AlertDialog.Builder(this)
                .setTitle("扫描结果")
                .setMessage(message)
                .setPositiveButton("确定", (dialog, which) -> {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> isScanning = true, 1500);
                })
                .setNegativeButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("QR", message);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> isScanning = true, 1500);
                })
                .setCancelable(false)
                .show();
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
    }
}
