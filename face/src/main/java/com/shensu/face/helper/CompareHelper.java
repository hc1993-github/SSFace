package com.shensu.face.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.FaceSimilar;
import com.arcsoft.face.ImageQualitySimilar;
import com.arcsoft.face.MaskInfo;
import com.arcsoft.face.enums.CompareModel;
import com.arcsoft.face.enums.DetectFaceOrientPriority;
import com.arcsoft.face.enums.DetectMode;
import com.arcsoft.face.enums.ExtractType;
import com.arcsoft.imageutil.ArcSoftImageFormat;
import com.arcsoft.imageutil.ArcSoftImageUtil;
import com.arcsoft.imageutil.ArcSoftImageUtilError;
import com.shensu.face.config.SPDefaultConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CompareHelper {
    int INIT_MASK = FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_FACE_DETECT | FaceEngine.ASF_GENDER |
            FaceEngine.ASF_AGE | FaceEngine.ASF_MASK_DETECT | FaceEngine.ASF_IMAGEQUALITY;
    int TYPE_IDENTIFY = 0;
    int TYPE_PHOTO = 1;
    Context mContext;
    CompareListener mCompareListener;
    FaceFeatureListener mFaceFeatureListener;
    FaceEngine mCompareEngine;
    FaceFeature mIdentifyBitmapFeature;
    FaceFeature mPhotoBitmapFeature;
    boolean mIsIdentifyFinish = false;
    boolean mIsPhotoFinish = false;
    ThreadPoolExecutor mExecutor;
    static CompareHelper instance;
    int mOpenCode = -1;
    Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    if (mCompareListener != null) {
                        mCompareListener.onSimilarScore((float) msg.obj);
                    }
                    break;
                case 2:
                    if(mCompareListener!=null){
                        mCompareListener.onIdentifyRecognize(mIsIdentifyFinish);
                    }
                    if(mFaceFeatureListener!=null){
                        mFaceFeatureListener.onFinish(mIdentifyBitmapFeature);
                    }
                    break;
                case 3:
                    if(mCompareListener!=null){
                        mCompareListener.onPhotoRecognize(mIsPhotoFinish);
                    }
                    break;
                case 4:
                    if(mCompareListener!=null){
                        mCompareListener.onImageQualityLow((Boolean) msg.obj);
                    }
                    break;
                case 5:
                    if(mCompareListener!=null){
                        mCompareListener.onWearMask((Boolean) msg.obj);
                    }
                    break;
            }
        }
    };

    public static CompareHelper getInstance() {
        if(instance==null){
            synchronized (CompareHelper.class){
                if(instance==null){
                    instance = new CompareHelper();
                }
            }
        }
        return instance;
    }

    private CompareHelper() {

    }

    public int open(Context context){
        this.mContext = context.getApplicationContext();
        mExecutor = new ThreadPoolExecutor(2,2,5, TimeUnit.SECONDS,new LinkedBlockingDeque<>());
        if (mCompareEngine == null) {
            mCompareEngine = new FaceEngine();
            mOpenCode = mCompareEngine.init(mContext, DetectMode.ASF_DETECT_MODE_IMAGE, DetectFaceOrientPriority.ASF_OP_ALL_OUT,
                    6, INIT_MASK);
        }
        return mOpenCode;
    }

    public boolean isOpen(){
        return mOpenCode==0;
    }

    public CompareListener getCompareListener() {
        return mCompareListener;
    }

    public void setCompareListener(CompareListener listener){
        this.mCompareListener = listener;
    }

    public FaceFeatureListener getFaceFeatureListener() {
        return mFaceFeatureListener;
    }

    public void setFaceFeatureListener(FaceFeatureListener faceFeatureListener) {
        this.mFaceFeatureListener = faceFeatureListener;
    }

    public void recognize(boolean isIdentify, Bitmap bitmap,float identifyQuality,float photoQuality){
        mExecutor.execute(() -> processBitmap(bitmap, isIdentify?TYPE_IDENTIFY:TYPE_PHOTO,identifyQuality,photoQuality));
    }

    public void clear(){
        mIsIdentifyFinish = false;
        mIsPhotoFinish = false;
        mIdentifyBitmapFeature = null;
        mPhotoBitmapFeature  = null;
        mCompareListener = null;
    }

    public void destroy() {
        mIsIdentifyFinish = false;
        mIsPhotoFinish = false;
        mOpenCode = -1;
        mCompareListener = null;
        mFaceFeatureListener = null;
        mHandler.removeCallbacksAndMessages(null);
        if (mCompareEngine != null) {
            mCompareEngine.unInit();
            mCompareEngine = null;
        }
        try {
            if(mExecutor!=null){
                mExecutor.shutdownNow();
                mExecutor.awaitTermination(10, TimeUnit.SECONDS);
                mExecutor = null;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void processBitmap(Bitmap bitmap, int type,float identifyQuality,float photoQuality) {
        if (bitmap == null || mCompareEngine == null) {
            return;
        }
        //宽度4倍对齐
        bitmap = ArcSoftImageUtil.getAlignedBitmap(bitmap, true);
        if (bitmap == null) {
            return;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        //bitmap转byte[]
        byte[] bgr24 = ArcSoftImageUtil.createImageData(bitmap.getWidth(), bitmap.getHeight(), ArcSoftImageFormat.BGR24);
        int transformCode = ArcSoftImageUtil.bitmapToImageData(bitmap, bgr24, ArcSoftImageFormat.BGR24);
        if (transformCode != ArcSoftImageUtilError.CODE_SUCCESS) {
            return;
        }
        //人脸检测
        List<FaceInfo> faceInfoList = new ArrayList<>();
        int detectCode = mCompareEngine.detectFaces(bgr24, width, height, FaceEngine.CP_PAF_BGR24, faceInfoList);
        if (detectCode != 0 || faceInfoList.isEmpty()) {
            if(type==TYPE_IDENTIFY){
                mIsIdentifyFinish = false;
                mHandler.sendEmptyMessage(2);
            }
            if(type==TYPE_PHOTO){
                mIsPhotoFinish = false;
                mHandler.sendEmptyMessage(3);
            }
            return;
        }
        //绘制bitmap
        bitmap = bitmap.copy(Bitmap.Config.RGB_565, true);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setStrokeWidth(10);
        paint.setColor(Color.YELLOW);
        if (!faceInfoList.isEmpty()) {
            for (int i = 0; i < faceInfoList.size(); i++) {
                //绘制人脸框
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(faceInfoList.get(i).getRect(), paint);
                //绘制人脸序号
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                paint.setTextSize((float) faceInfoList.get(i).getRect().width() / 2);
                canvas.drawText("" + i, faceInfoList.get(i).getRect().left, faceInfoList.get(i).getRect().top, paint);
            }
        }
        //人脸属性检测
        int faceProcessCode = mCompareEngine.process(bgr24, width, height, FaceEngine.CP_PAF_BGR24, faceInfoList,
                FaceEngine.ASF_AGE | FaceEngine.ASF_GENDER | FaceEngine.ASF_MASK_DETECT);
        if (faceProcessCode != ErrorInfo.MOK) {
            return;
        }
        List<MaskInfo> maskInfoList = new ArrayList<>();
        int maskInfoCode = mCompareEngine.getMask(maskInfoList);
        if (maskInfoCode != ErrorInfo.MOK) {
            return;
        }
        int isMask = MaskInfo.UNKNOWN;
        if (!maskInfoList.isEmpty()) {
            isMask = maskInfoList.get(0).getMask();
        }
        if ((isMask == MaskInfo.WORN || isMask == MaskInfo.UNKNOWN)) {
            //佩戴了口罩或者图片不清晰
            Message message = Message.obtain();
            message.what = 5;
            message.obj = (type == TYPE_IDENTIFY);
            mHandler.sendMessage(message);
            return;
        }
        //图像质量检测
        ImageQualitySimilar imageQualitySimilar = new ImageQualitySimilar();
        int qualityCode = mCompareEngine.imageQualityDetect(bgr24, width, height, FaceEngine.CP_PAF_BGR24, faceInfoList.get(0),
                isMask, imageQualitySimilar);
        if (qualityCode != ErrorInfo.MOK) {
            Message message = Message.obtain();
            message.what = 4;
            message.obj = (type == TYPE_IDENTIFY);
            mHandler.sendMessage(message);
            return;
        }
        float quality = imageQualitySimilar.getScore();
//        float destQuality = SPDefaultConfig.getImageQualityNoMaskRegisterThreshold(mContext);
        float destQuality = (type==TYPE_IDENTIFY?identifyQuality:photoQuality);
        if (quality < destQuality) {
            Message message = Message.obtain();
            message.what = 4;
            message.obj = (type == TYPE_IDENTIFY);
            mHandler.sendMessage(message);
            return;
        }
        if (!faceInfoList.isEmpty()) {
            if (type == TYPE_IDENTIFY) {
                mIdentifyBitmapFeature = new FaceFeature();
                int res = mCompareEngine.extractFaceFeature(bgr24, width, height, FaceEngine.CP_PAF_BGR24, faceInfoList.get(0),
                        ExtractType.REGISTER, isMask, mIdentifyBitmapFeature);
                if (res != ErrorInfo.MOK) {
                    mIdentifyBitmapFeature = null;
                }
                if (mIdentifyBitmapFeature != null) {
                    mIsIdentifyFinish = true;
                }else {
                    mIsIdentifyFinish = false;
                }
                mHandler.sendEmptyMessage(2);
            } else {
                mPhotoBitmapFeature = new FaceFeature();
                int res = mCompareEngine.extractFaceFeature(bgr24, width, height, FaceEngine.CP_PAF_BGR24, faceInfoList.get(0),
                        ExtractType.REGISTER, isMask, mPhotoBitmapFeature);
                if (res != ErrorInfo.MOK) {
                    mPhotoBitmapFeature = null;
                }
                if (mPhotoBitmapFeature != null) {
                    mIsPhotoFinish = true;
                }else {
                    mIsPhotoFinish = false;
                }
                mHandler.sendEmptyMessage(3);
            }
            if(mIsIdentifyFinish && mIsPhotoFinish){
                similarCheck();
            }
        }
    }

    private void similarCheck() {
        mExecutor.execute(() -> {
            FaceSimilar faceSimilar = new FaceSimilar();
            int result = mCompareEngine.compareFaceFeature(mIdentifyBitmapFeature, mPhotoBitmapFeature, CompareModel.ID_CARD,faceSimilar);
            Message message = Message.obtain();
            message.what = 1;
            if (result == ErrorInfo.MOK) {
                message.obj = faceSimilar.getScore();
            } else {
                message.obj = -1f;
            }
            mHandler.sendMessage(message);
        });
    }
}
