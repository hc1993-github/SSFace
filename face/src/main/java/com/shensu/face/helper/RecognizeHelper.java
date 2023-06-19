package com.shensu.face.helper;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Toast;

//import androidx.core.content.ContextCompat;

import com.arcsoft.face.AgeInfo;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.FaceInfo;
import com.arcsoft.face.FaceSimilar;
import com.arcsoft.face.GenderInfo;
import com.arcsoft.face.LivenessInfo;
import com.arcsoft.face.LivenessParam;
import com.arcsoft.face.MaskInfo;
import com.arcsoft.face.enums.DetectFaceOrientPriority;
import com.arcsoft.face.enums.DetectMode;
import com.arcsoft.face.enums.ExtractType;
import com.shensu.face.R;
import com.shensu.face.camera.CameraListener;
import com.shensu.face.camera.CompareResult;
import com.shensu.face.camera.DualCameraHelper;
import com.shensu.face.camera.FaceHelper;
import com.shensu.face.camera.FacePreviewInfo;
import com.shensu.face.camera.FaceRectTransformer;
import com.shensu.face.camera.RecognizeCallback;
import com.shensu.face.camera.RecognizeConfiguration;
import com.shensu.face.config.LivenessType;
import com.shensu.face.config.PreviewConfig;
import com.shensu.face.config.RecognizeColor;
import com.shensu.face.config.SPDefaultConfig;
import com.shensu.face.view.FaceRectView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class RecognizeHelper implements RecognizeCallback {
    public static final int COMPARE_OPEN_FAILED = -2;
    public static final int RECOGNIZE_OPEN_FAILED = -1;
    Activity mActivity;
    PermissionListener mListener;
    RecognizeListener mRecognizeListener;
    String[] NEEDED_PERMISSIONS = new String[]{Manifest.permission.CAMERA, Manifest.permission.READ_PHONE_STATE};
    ConcurrentHashMap<Integer, Integer> mLivenessMap;
    FaceEngine mFtEngine;
    FaceEngine mFrEngine;
    FaceEngine mFlEngine;
    FaceEngine mCommonEngine;
    PreviewConfig mPreviewConfig;
    TextureView mTextureView;
    FaceRectView mFaceRectView;
    DualCameraHelper mDualCameraHelper;
    FaceRectTransformer mFaceRectTransformer;
    Camera.Size mPreviewSize;
    FaceHelper mFaceHelper;
    byte[] mIrNv21;
    boolean mIsRecognized = true;
    boolean mIsCompared = true;
    ReentrantLock mLivenessDetectLock = new ReentrantLock();
    FaceFeature mFaceFeature;
    static RecognizeHelper instance;
    ExecutorService mLivenessExecutor = new ThreadPoolExecutor(2, 2,
            5, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());
    byte[] mNv21;
    Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 3:
                    mIsRecognized = true;
                    if (mRecognizeListener != null) {
                        mRecognizeListener.onRecognized((CompareResult) msg.obj);
                    }
                    break;
                case 4:
                    if (mRecognizeListener != null) {
                        mRecognizeListener.onNoticeChanged((String) msg.obj);
                    }
                    break;
                case 5:
                    if(mRecognizeListener!=null){
                        mRecognizeListener.onCompareSimilar((Float) msg.obj,mNv21);
                    }
                    break;
            }
        }
    };

    public static RecognizeHelper getInstance() {
        if(instance==null){
            synchronized (RecognizeHelper.class){
                if(instance==null){
                    instance = new RecognizeHelper();
                }
            }
        }
        return instance;
    }

    private RecognizeHelper() {

    }

    public void create(Activity activity,TextureView textureView,FaceRectView faceRectView,PermissionListener permissionListener, RecognizeListener recognizeListener) {
        mActivity = activity;
        mListener = permissionListener;
        mRecognizeListener = recognizeListener;
        mLivenessMap = new ConcurrentHashMap<>();
        mFtEngine = new FaceEngine();
        int ftCode = mFtEngine.init(mActivity, DetectMode.ASF_DETECT_MODE_VIDEO, SPDefaultConfig.getFtOrient(mActivity),
                SPDefaultConfig.getRecognizeMaxDetectFaceNum(mActivity), FaceEngine.ASF_FACE_DETECT);
        mFrEngine = new FaceEngine();
        int frCode = mFrEngine.init(mActivity, DetectMode.ASF_DETECT_MODE_IMAGE, DetectFaceOrientPriority.ASF_OP_0_ONLY,
                10, FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_IMAGEQUALITY);
        mCommonEngine = new FaceEngine();
        int comCode = mCommonEngine.init(mActivity, DetectMode.ASF_DETECT_MODE_VIDEO, DetectFaceOrientPriority.ASF_OP_ALL_OUT,
                6, FaceEngine.ASF_FACE_RECOGNITION | FaceEngine.ASF_FACE_DETECT | FaceEngine.ASF_GENDER |
                        FaceEngine.ASF_AGE | FaceEngine.ASF_MASK_DETECT | FaceEngine.ASF_IMAGEQUALITY);
        LivenessType liveNessType;
        int liveNessMask;
        String liveNessTypeStr = SPDefaultConfig.getLivenessDetectType(mActivity);
        if (liveNessTypeStr.equals(mActivity.getString(R.string.value_liveness_type_ir))) {
            liveNessType = LivenessType.IR;
        } else {
            liveNessType = LivenessType.RGB;
        }
        if (DualCameraHelper.canOpenDualCamera() && liveNessType == LivenessType.IR) {
            liveNessMask = FaceEngine.ASF_LIVENESS | FaceEngine.ASF_IR_LIVENESS | FaceEngine.ASF_FACE_DETECT;
        } else {
            liveNessMask = FaceEngine.ASF_LIVENESS;
        }
        int dualCameraHorizontalOffset = SPDefaultConfig.getDualCameraHorizontalOffset(mActivity);
        int dualCameraVerticalOffset = SPDefaultConfig.getDualCameraVerticalOffset(mActivity);
        if (dualCameraHorizontalOffset != 0 || dualCameraVerticalOffset != 0) {
            liveNessMask |= FaceEngine.ASF_UPDATE_FACEDATA;
        }
        mFlEngine = new FaceEngine();
        LivenessParam livenessParam = new LivenessParam(SPDefaultConfig.getRgbLivenessThreshold(mActivity),
                SPDefaultConfig.getIrLivenessThreshold(mActivity), SPDefaultConfig.getLivenessFqThreshold(mActivity));
        int flCode = mFlEngine.init(mActivity, DetectMode.ASF_DETECT_MODE_IMAGE, SPDefaultConfig.getFtOrient(mActivity),
                SPDefaultConfig.getRecognizeMaxDetectFaceNum(mActivity), liveNessMask);
        mFlEngine.setLivenessParam(livenessParam);

        mPreviewConfig = new PreviewConfig(Camera.CameraInfo.CAMERA_FACING_BACK,
                Camera.CameraInfo.CAMERA_FACING_FRONT,
                Integer.parseInt(SPDefaultConfig.getRgbCameraAdditionalRotation(mActivity)),
                Integer.parseInt(SPDefaultConfig.getIrCameraAdditionalRotation(mActivity)));
        mTextureView = textureView;
        mFaceRectView = faceRectView;
        if((ftCode!=0 || frCode!=0 || comCode!=0 || flCode!=0) && recognizeListener!=null){
            recognizeListener.onOpenFail(RECOGNIZE_OPEN_FAILED);
        }
        mTextureView.post(() -> {
            if (checkPermissions(NEEDED_PERMISSIONS)) {
                switchCamera(true);
            } else {
                if (mListener != null) {
                    mListener.noAllGranted(NEEDED_PERMISSIONS);
                }
            }
        });
    }

    public void switchCamera(boolean isCameraFacingBackPriority) {
        releaseCamera();
        if (mTextureView != null && mFaceRectView != null) {
            mDualCameraHelper = new DualCameraHelper.Builder()
                    .previewViewSize(new Point(mTextureView.getMeasuredWidth(), mTextureView.getMeasuredHeight()))
                    .rotation(mActivity.getWindowManager().getDefaultDisplay().getRotation())
                    .specificCameraId(isCameraFacingBackPriority ? mPreviewConfig.getRgbCameraId():mPreviewConfig.getIrCameraId())
                    .isMirror(SPDefaultConfig.isDrawRgbPreviewHorizontalMirror(mActivity))
                    .additionalRotation(Integer.parseInt(SPDefaultConfig.getRgbCameraAdditionalRotation(mActivity)))
                    .previewViewSize(loadPreviewSize())
                    .previewOn(mTextureView)
                    .cameraListener(new CameraListener() {
                        @Override
                        public void onCameraOpened(Camera camera, int cameraId, int displayOrientation, boolean isMirror) {
                            Camera.Size previewSizeRgb = camera.getParameters().getPreviewSize();
                            ViewGroup.LayoutParams layoutParams = adjustPreviewViewSize(mTextureView,
                                    mTextureView, mFaceRectView,
                                    previewSizeRgb, displayOrientation, 1.0f);
                            mFaceRectTransformer = new FaceRectTransformer(previewSizeRgb.width, previewSizeRgb.height,
                                    layoutParams.width, layoutParams.height,
                                    displayOrientation, cameraId, isMirror,
                                    SPDefaultConfig.isDrawRgbRectHorizontalMirror(mActivity),
                                    SPDefaultConfig.isDrawRgbRectVerticalMirror(mActivity));
                            mFaceRectTransformer.setMirrorHorizontal(true);
                            Camera.Size lastPreviewSize = mPreviewSize;
                            mPreviewSize = camera.getParameters().getPreviewSize();
                            initFaceHelper(lastPreviewSize);
                            mFaceHelper.setRgbFaceRectTransformer(mFaceRectTransformer);
                        }

                        @Override
                        public void onPreview(byte[] data, Camera camera) {
                            mFaceRectView.clearFaceInfo();
                            List<FacePreviewInfo> facePreviewInfoList = onPreviewFrame(data);
                            if (facePreviewInfoList != null && mFaceRectTransformer != null) {
                                drawPreviewInfo(facePreviewInfoList, data, camera);
                            }
                        }

                        @Override
                        public void onCameraClosed() {

                        }

                        @Override
                        public void onCameraError(Exception e) {

                        }

                        @Override
                        public void onCameraConfigurationChanged(int cameraID, int displayOrientation) {
                            if (mFaceRectTransformer != null) {
                                mFaceRectTransformer.setCameraDisplayOrientation(displayOrientation);
                            }
                        }
                    }).build();
            mDualCameraHelper.init();
            mDualCameraHelper.start();
        }
    }

    public void doRecognize() {
        mIsRecognized = false;
    }

    public void startCompare(){
        mIsCompared = false;
    }

    public void stopCompare(){
        mIsCompared = true;
    }

    public void resume() {
        if (mDualCameraHelper != null) {
            mDualCameraHelper.start();
        }
    }

    public void pause() {
        if (mDualCameraHelper != null) {
            mDualCameraHelper.stop();
        }
    }

    public void destroy() {
        mFaceFeature = null;
        mIsRecognized = true;
        mHandler.removeCallbacksAndMessages(null);
        releaseCamera();
        if(CompareHelper.getInstance().isOpen()){
            CompareHelper.getInstance().destroy();
        }
        if (mFtEngine != null) {
            mFtEngine.unInit();
            mFtEngine = null;
        }
        if (mFrEngine != null) {
            mFrEngine.unInit();
            mFrEngine = null;
        }
        if (mFlEngine != null) {
            mFlEngine.unInit();
            mFlEngine = null;
        }
        if(mCommonEngine!=null){
            mCommonEngine.unInit();
            mCommonEngine = null;
        }
        try {
            if(mLivenessExecutor!=null){
                mLivenessExecutor.shutdownNow();
                mLivenessExecutor.awaitTermination(10, TimeUnit.SECONDS);
                mLivenessExecutor = null;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public Camera.Size getPreviewSize() {
        return mPreviewSize;
    }

    private void releaseCamera() {
        if (mDualCameraHelper != null) {
            mDualCameraHelper.release();
            mDualCameraHelper = null;
        }
    }

    private void drawPreviewInfo(List<FacePreviewInfo> facePreviewInfoList, byte[] nv21, Camera camera) {
        if (mFaceRectTransformer != null) {
            List<FaceRectView.DrawInfo> rgbDrawInfoList = getDrawInfo(facePreviewInfoList, LivenessType.RGB);
            mFaceRectView.drawRealtimeFaceInfo(rgbDrawInfoList);
            if(!mIsCompared && mFaceFeature!=null){
                for(int i=0;i<rgbDrawInfoList.size();i++){
                    if(rgbDrawInfoList.get(i).getLiveness()== LivenessInfo.ALIVE){
                        mIsCompared = true;
                        mLivenessExecutor.execute(() -> faceCheck(nv21,camera));
                        break;
                    }
                }
            }
        }
    }

    public void getMainFeature(Bitmap bitmap,CompareListener compareListener,float identifyQuality,float photoQualify){
        int openCode = -1;
        if(!CompareHelper.getInstance().isOpen()){
            openCode = CompareHelper.getInstance().open(mActivity);
        }
        if(openCode==0){
            if(CompareHelper.getInstance().getCompareListener()==null){
                CompareHelper.getInstance().setCompareListener(compareListener);
            }
            CompareHelper.getInstance().setFaceFeatureListener(faceFeature -> {
                if(faceFeature!=null){
                    mFaceFeature = faceFeature;
                }
            });
            CompareHelper.getInstance().recognize(true,bitmap,identifyQuality,photoQualify);
        }else {
            if(mRecognizeListener!=null){
                mRecognizeListener.onOpenFail(COMPARE_OPEN_FAILED);
            }
        }
    }

    private void faceCheck(byte[] nv21,Camera camera) {
        Camera.Size previewSizeRgb = camera.getParameters().getPreviewSize();
        List<FaceInfo> mFaceInfoList = new ArrayList<>();
        FaceFeature faceFeature = new FaceFeature();
        int code = mCommonEngine.detectFaces(nv21, previewSizeRgb.width,previewSizeRgb.height, FaceEngine.CP_PAF_NV21, mFaceInfoList);
        if (code == 0 && !mFaceInfoList.isEmpty()) {
            int res = mCommonEngine.extractFaceFeature(nv21, previewSizeRgb.width, previewSizeRgb.height, FaceEngine.CP_PAF_NV21, mFaceInfoList.get(0),ExtractType.RECOGNIZE, MaskInfo.NOT_WORN, faceFeature);
            if (res == 0) {
                FaceSimilar faceSimilar = new FaceSimilar();
                int compareResult = mCommonEngine.compareFaceFeature(mFaceFeature,faceFeature, faceSimilar);
                if (compareResult == ErrorInfo.MOK) {
                    mIsCompared = true;
                    mNv21 = nv21;
                    Message message = Message.obtain();
                    message.what = 5;
                    message.obj = faceSimilar.getScore();
                    mHandler.sendMessage(message);
                }else {
                    mIsCompared = false;
                }
            }
        }
    }

    private List<FaceRectView.DrawInfo> getDrawInfo(List<FacePreviewInfo> facePreviewInfoList, LivenessType livenessType) {
        List<FaceRectView.DrawInfo> drawInfoList = new ArrayList<>();
        for (int i = 0; i < facePreviewInfoList.size(); i++) {
            int liveness = livenessType == LivenessType.RGB ? facePreviewInfoList.get(i).getRgbLiveness() : facePreviewInfoList.get(i).getIrLiveness();
            Rect rect = livenessType == LivenessType.RGB ?
                    facePreviewInfoList.get(i).getRgbTransformedRect() :
                    facePreviewInfoList.get(i).getIrTransformedRect();
            // 根据识别结果和活体结果设置颜色
            int color;
            String name;
            switch (liveness) {
                case LivenessInfo.ALIVE:
                    color = RecognizeColor.COLOR_SUCCESS;
                    name = "ALIVE";
                    break;
                case LivenessInfo.NOT_ALIVE:
                    color = RecognizeColor.COLOR_FAILED;
                    name = "NOT_ALIVE";
                    break;
                default:
                    color = RecognizeColor.COLOR_UNKNOWN;
                    name = "UNKNOWN";
                    break;
            }

            drawInfoList.add(new FaceRectView.DrawInfo(rect, GenderInfo.UNKNOWN,
                    AgeInfo.UNKNOWN_AGE, liveness, color, name));
        }
        return drawInfoList;
    }

    private Point loadPreviewSize() {
        String[] size = SPDefaultConfig.getPreviewSize(mActivity).split("x");
        return new Point(Integer.parseInt(size[0]), Integer.parseInt(size[1]));
    }

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(mActivity, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    private ViewGroup.LayoutParams adjustPreviewViewSize(View rgbPreview, View previewView, FaceRectView faceRectView, Camera.Size previewSize, int displayOrientation, float scale) {
        ViewGroup.LayoutParams layoutParams = previewView.getLayoutParams();
        int measuredWidth = previewView.getMeasuredWidth();
        int measuredHeight = previewView.getMeasuredHeight();
        float ratio = ((float) previewSize.height) / (float) previewSize.width;
        if (ratio > 1) {
            ratio = 1 / ratio;
        }
        if (displayOrientation % 180 == 0) {
            layoutParams.width = measuredWidth;
            layoutParams.height = (int) (measuredWidth * ratio);
        } else {
            layoutParams.height = measuredHeight;
            layoutParams.width = (int) (measuredHeight * ratio);
        }
        if (scale < 1f) {
            ViewGroup.LayoutParams rgbParam = rgbPreview.getLayoutParams();
            layoutParams.width = (int) (rgbParam.width * scale);
            layoutParams.height = (int) (rgbParam.height * scale);
        } else {
            layoutParams.width *= scale;
            layoutParams.height *= scale;
        }

//        DisplayMetrics metrics = new DisplayMetrics();
//        mActivity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
//
//        if (layoutParams.width >= metrics.widthPixels) {
//            float viewRatio = layoutParams.width / ((float) metrics.widthPixels);
//            layoutParams.width /= viewRatio;
//            layoutParams.height /= viewRatio;
//        }
//        if (layoutParams.height >= metrics.heightPixels) {
//            float viewRatio = layoutParams.height / ((float) metrics.heightPixels);
//            layoutParams.width /= viewRatio;
//            layoutParams.height /= viewRatio;
//        }

        previewView.setLayoutParams(layoutParams);
        faceRectView.setLayoutParams(layoutParams);
        return layoutParams;
    }

    private void initFaceHelper(Camera.Size lastPreviewSize) {
        if (mFaceHelper == null ||
                lastPreviewSize == null ||
                lastPreviewSize.width != mPreviewSize.width || lastPreviewSize.height != mPreviewSize.height) {
            Integer trackedFaceCount = null;
            // 记录切换时的人脸序号
            if (mFaceHelper != null) {
                trackedFaceCount = mFaceHelper.getTrackedFaceCount();
                mFaceHelper.release();
            }
            Context context = mActivity.getApplicationContext();

            mFaceHelper = new FaceHelper.Builder()
                    .ftEngine(mFtEngine)
                    .frEngine(mFrEngine)
                    .previewSize(mPreviewSize)
                    .onlyDetectLiveness(true)
                    .recognizeConfiguration(new RecognizeConfiguration.Builder().keepMaxFace(true).build())
                    .trackedFaceCount(trackedFaceCount == null ? SPDefaultConfig.getTrackedFaceCount(context) : trackedFaceCount)
                    .recognizeCallback(this)
                    .build();
        }
    }

    private List<FacePreviewInfo> onPreviewFrame(byte[] nv21) {
        List<FacePreviewInfo> facePreviewInfoList = mFaceHelper.onPreviewFrame(nv21, mIrNv21, !mIsRecognized);
        clearLeftFace(facePreviewInfoList);
        return processLiveness(nv21, mIrNv21, facePreviewInfoList);
    }

    private List<FacePreviewInfo> processLiveness(byte[] nv21, byte[] mIrNv21, List<FacePreviewInfo> previewInfoList) {
        if (previewInfoList == null || previewInfoList.size() == 0) {
            return null;
        }
        if (!mLivenessDetectLock.isLocked() && mLivenessExecutor != null) {
            mLivenessExecutor.execute(() -> {
                List<FacePreviewInfo> facePreviewInfoList = new LinkedList<>(previewInfoList);
                mLivenessDetectLock.lock();
                try {
                    int processRgbLivenessCode;
                    if (facePreviewInfoList.isEmpty()) {

                    } else {
                        synchronized (mFlEngine) {
                            processRgbLivenessCode = mFlEngine.process(nv21, mPreviewSize.width, mPreviewSize.height, FaceEngine.CP_PAF_NV21,
                                    new ArrayList<>(Collections.singletonList(facePreviewInfoList.get(0).getFaceInfoRgb())), FaceEngine.ASF_LIVENESS);
                        }
                        if (processRgbLivenessCode != ErrorInfo.MOK) {

                        } else {
                            List<LivenessInfo> rgbLivenessInfoList = new ArrayList<>();
                            int getRgbLivenessCode = mFlEngine.getLiveness(rgbLivenessInfoList);
                            if (getRgbLivenessCode != ErrorInfo.MOK) {

                            } else {
                                mLivenessMap.put(facePreviewInfoList.get(0).getTrackId(), rgbLivenessInfoList.get(0).getLiveness());
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    mLivenessDetectLock.unlock();
                }
            });
        }
        for (FacePreviewInfo facePreviewInfo : previewInfoList) {
            Integer rgbLiveness = mLivenessMap.get(facePreviewInfo.getTrackId());
            if (rgbLiveness != null) {
                facePreviewInfo.setRgbLiveness(rgbLiveness);
            }
        }
        return previewInfoList;
    }

    private void clearLeftFace(List<FacePreviewInfo> facePreviewInfoList) {
        Enumeration<Integer> keys = mLivenessMap.keys();
        while (keys.hasMoreElements()) {
            int key = keys.nextElement();
            boolean contained = false;
            for (FacePreviewInfo facePreviewInfo : facePreviewInfoList) {
                if (facePreviewInfo.getTrackId() == key) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                mLivenessMap.remove(key);
            }
        }
    }

    @Override
    public void onRecognized(CompareResult compareResult, Integer liveness, boolean similarPass) {
        if (similarPass) {
            Message message = Message.obtain();
            message.what = 3;
            message.obj = compareResult;
            mHandler.sendMessage(message);
        }
    }

    @Override
    public void onNoticeChanged(String notice) {
        Message message = Message.obtain();
        message.what = 4;
        message.obj = notice;
        mHandler.sendMessage(message);
    }
}
