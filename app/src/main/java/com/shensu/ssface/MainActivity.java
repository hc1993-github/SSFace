package com.shensu.ssface;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.TextureView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.shensu.face.camera.CompareResult;
import com.shensu.face.helper.ActiveHelper;
import com.shensu.face.helper.CompareListener;
import com.shensu.face.helper.RecognizeHelper;
import com.shensu.face.helper.RecognizeListener;
import com.shensu.face.view.FaceRectView;

import java.io.ByteArrayOutputStream;

public class MainActivity extends AppCompatActivity{
    String TAG = "MainActivity---";
    String[] NEEDED_PERMISSIONS = new String[]{Manifest.permission.CAMERA,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE};
    TextView tv_active;
    TextView tv_open;
    TextView tv_compare;
    TextView tv_exit;
    TextView tv_similar;
    ImageView iv_photo;
    TextureView ttv;
    FaceRectView frv;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        checkPermissions(NEEDED_PERMISSIONS);
        tv_active = findViewById(R.id.tv_active);
        tv_open = findViewById(R.id.tv_open);
        tv_compare = findViewById(R.id.tv_compare);
        tv_similar = findViewById(R.id.tv_similar);
        iv_photo = findViewById(R.id.iv_photo);
        tv_exit = findViewById(R.id.tv_exit);
        ttv = findViewById(R.id.ttv);
        frv = findViewById(R.id.frv);
        tv_active.setOnClickListener(v -> {
            int result = ActiveHelper.getInstance().activeOnline(MainActivity.this, "085K-1119-72AN-LK9P", "BT9o1KgXoL2b5esHmhCYtw6tRHLGZjHPFqF1g6VqFTnQ", "A9VsgWuh5c8RgvLqyLP3VpAL6bmqzn36nnPgaSErhg7m");
            Toast.makeText(MainActivity.this,"activeOnline:"+result,Toast.LENGTH_SHORT).show();
        });
        tv_open.setOnClickListener(v -> {
            RecognizeHelper.getInstance().create(this, ttv, frv, null, new RecognizeListener() {
                @Override
                public void onOpenFail(int failCode) {

                }

                @Override
                public void onRecognized(CompareResult compareResult) {

                }

                @Override
                public void onNoticeChanged(String notice) {

                }

                @Override
                public void onCompareSimilar(float similar,byte[] nv21) {
                    tv_similar.setText(similar+"");
                    if(similar>0.8f){
                        Camera.Size size = RecognizeHelper.getInstance().getPreviewSize();
                        YuvImage image = new YuvImage(nv21, ImageFormat.NV21,size.width,size.height, null);
                        if(image!=null){
                            ByteArrayOutputStream stream = new ByteArrayOutputStream();
                            image.compressToJpeg(new Rect(0, 0, size.width,size.height),100, stream);
                            Bitmap bitmap = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size());
                            iv_photo.setImageBitmap(bitmap);
                        }
                    }else {
                        RecognizeHelper.getInstance().startCompare();
                    }
                }
            });
            RecognizeHelper.getInstance().getMainFeature(BitmapFactory.decodeResource(getResources(), R.drawable.test1), new CompareListener() {
                @Override
                public void onSimilarScore(float score) {

                }

                @Override
                public void onIdentifyRecognize(boolean isSuccess) {

                }

                @Override
                public void onPhotoRecognize(boolean isSuccess) {

                }

                @Override
                public void onImageQualityLow(boolean isMainImage) {

                }

                @Override
                public void onWearMask(boolean isMainImage) {

                }
            },0.63f,0.63f);
        });
        tv_compare.setOnClickListener(v -> RecognizeHelper.getInstance().startCompare());
        tv_exit.setOnClickListener(v -> {
            RecognizeHelper.getInstance().stopCompare();
            finish();
        });
    }

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(this, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    @Override
    protected void onDestroy() {
        RecognizeHelper.getInstance().destroy();
        super.onDestroy();
    }
}