package com.shensu.face.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.v4.content.ContextCompat;

//import androidx.core.content.ContextCompat;

import com.arcsoft.imageutil.ArcSoftImageFormat;
import com.arcsoft.imageutil.ArcSoftImageUtil;
import com.arcsoft.imageutil.ArcSoftImageUtilError;
import com.shensu.face.R;
import com.shensu.face.config.ApplyConfig;
import com.shensu.face.db.FaceDao;
import com.shensu.face.db.FaceDatabase;
import com.shensu.face.db.FaceEntity;
import com.shensu.face.db.FaceRepository;
import com.shensu.face.db.FaceServer;
import com.shensu.face.util.FileUtil;
import com.shensu.face.util.ImageUtil;

import java.io.File;

import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.ObservableSource;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class RegisterHelper {
    Context mContext;
    RegisterListener mListener;
    String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    FaceRepository mFaceRepository;
    int PAGE_SIZE = 20;
    Disposable mDisposable;

    public RegisterHelper(Context context,RegisterListener listener) {
        this.mContext = context;
        this.mListener = listener;
    }

    public void registerSinglePic(Uri uri,String name){
        Bitmap bitmap = ImageUtil.uriToScaledBitmap(mContext, uri, ImageUtil.DEFAULT_MAX_WIDTH, ImageUtil.DEFAULT_MAX_HEIGHT);
        register(bitmap,name);
    }

    public void registerMultiplePics(PermissionListener listener){
        if(checkPermissions(NEEDED_PERMISSIONS)){
            registerFromFile(mContext,new File(ApplyConfig.DEFAULT_REGISTER_FACES_DIR));
        }else {
            if(listener!=null){
                listener.noAllGranted(NEEDED_PERMISSIONS);
            }
        }
    }

    private void registerFromFile(Context context, File dir) {
        if (!dir.exists()) {
            if(mListener!=null){
                mListener.onMultiRegisterFinish(0,0,0,context.getString(R.string.please_put_photos, dir.getAbsolutePath()));
            }
            return;
        }
        File[] files = dir.listFiles((dir1, name) -> {
            String nameLowerCase = name.toLowerCase();
            return nameLowerCase.endsWith(".jpg") || nameLowerCase.endsWith(".jpeg") || nameLowerCase.endsWith(".png");
        });
        if (!dir.exists()) {
            if(mListener!=null){
                mListener.onMultiRegisterFinish(0,0,0,context.getString(R.string.please_put_photos, dir.getAbsolutePath()));
            }
            return;
        }
        int total = files.length;
        final int[] failed = {0};
        final int[] success = {0};
        Observable.fromArray(files)
                .flatMap((Function<File, ObservableSource<Boolean>>) file -> {
                    byte[] bytes = FileUtil.fileToData(file);
                    String name = file.getName();
                    int suffixIndex = name.indexOf(".");
                    if (suffixIndex > 0) {
                        name = name.substring(0, suffixIndex);
                    }
                    FaceEntity faceEntity;
                    faceEntity = mFaceRepository.registerJpeg(context, bytes, name);
                    success[0]++;
                    if (faceEntity == null) {
                        failed[0]++;
                    }
                    FaceEntity finalFaceEntity = faceEntity;
                    return observer -> observer.onNext(finalFaceEntity == null);
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Boolean>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        mDisposable = d;
                    }

                    @Override
                    public void onNext(Boolean res) {
                        int succeedSize = success[0];
                        int failedSize = failed[0];
                        if (total == succeedSize + failedSize) {
                            mListener.onMultiRegisterFinish(success[0], failed[0], total, null);
                        } else {
                            mListener.onMultiRegisterProcess(success[0], failed[0], total);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        mListener.onMultiRegisterFinish(success[0], failed[0], total, e.getMessage());
                        mDisposable.dispose();
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private void register(Bitmap bitmap,String name){
        initRepository();
        Bitmap alignedBitmap = ArcSoftImageUtil.getAlignedBitmap(bitmap, true);
        Observable.create((ObservableOnSubscribe<byte[]>) emitter -> {
            byte[] bgr24Data = ArcSoftImageUtil.createImageData(alignedBitmap.getWidth(), alignedBitmap.getHeight(), ArcSoftImageFormat.BGR24);
            int transformCode = ArcSoftImageUtil.bitmapToImageData(alignedBitmap, bgr24Data, ArcSoftImageFormat.BGR24);
            if (transformCode == ArcSoftImageUtilError.CODE_SUCCESS) {
                emitter.onNext(bgr24Data);
            } else {
                emitter.onError(new Exception("transform failed, code is " + transformCode));
            }
        })
                .flatMap((Function<byte[], ObservableSource<FaceEntity>>) bgr24Data -> {
                    Observable<FaceEntity> faceEntityObservable = Observable.just(mFaceRepository.registerBgr24(
                            mContext, bgr24Data,
                            alignedBitmap.getWidth(), alignedBitmap.getHeight(),
                            name));
                    // 注册成功时，数据也同步更新下
                    //loadData(true);
                    //faceRepository.reload();
                    return faceEntityObservable;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<FaceEntity>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(FaceEntity faceEntity) {
                        if (faceEntity != null) {
                            mListener.onRegisterFinished(true);
                        } else {
                            mListener.onRegisterFinished(false);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        mListener.onRegisterFinished(false);
                    }

                    @Override
                    public void onComplete() {
                    }
                });
    }

    private void initRepository(){
        if (mFaceRepository == null) {
            FaceServer instance = FaceServer.getInstance();
            FaceDao faceDao = FaceDatabase.getInstance(mContext).faceDao();
            instance.init(mContext.getApplicationContext(), new FaceServer.OnInitFinishedCallback() {
                @Override
                public void onFinished(int faceCount) {

                }
            });
            mFaceRepository = new FaceRepository(PAGE_SIZE, faceDao, instance);
        }
    }

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(mContext, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }
}
