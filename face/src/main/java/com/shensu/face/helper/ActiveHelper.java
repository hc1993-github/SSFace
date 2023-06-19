package com.shensu.face.helper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.support.v4.content.ContextCompat;

//import androidx.core.content.ContextCompat;

import com.arcsoft.face.ActiveFileInfo;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;

public class ActiveHelper {
    Context mContext;
    PermissionListener mListener;
    String[] NEEDED_PERMISSIONS_ONLINE = new String[]{Manifest.permission.READ_PHONE_STATE};
    String[] NEEDED_PERMISSIONS_OFFLINE = new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_EXTERNAL_STORAGE};
    static ActiveHelper instance;
    private ActiveHelper() {
    }

    public static ActiveHelper getInstance() {
        if(instance==null){
            synchronized (ActiveHelper.class){
                if(instance==null){
                    instance = new ActiveHelper();
                }
            }
        }
        return instance;
    }

    public void setListener(PermissionListener mListener) {
        this.mListener = mListener;
    }

    public int activeOnline(Context context,String activeKey, String appId, String sdkKey) {

        this.mContext = context.getApplicationContext();
        if (checkPermissions(NEEDED_PERMISSIONS_ONLINE)) {
            return activeOn(activeKey, appId, sdkKey);
        } else {
            if (mListener != null) {
                mListener.noAllGranted(NEEDED_PERMISSIONS_ONLINE);
            }
        }
        return -1;
    }

    public int activeOffLine(Context context,String activeFilePath) {
        this.mContext = context.getApplicationContext();
        if (checkPermissions(NEEDED_PERMISSIONS_OFFLINE)) {
            return activeOff(activeFilePath);
        } else {
            if (mListener != null) {
                mListener.noAllGranted(NEEDED_PERMISSIONS_OFFLINE);
            }
        }
        return -1;
    }

    private boolean checkPermissions(String[] neededPermissions) {
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(mContext, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    private int activeOn(String activeKey, String appId, String sdkKey) {
        return FaceEngine.activeOnline(mContext, activeKey, appId, sdkKey);
    }

    private int activeOff(String filePath) {
        return FaceEngine.activeOffline(mContext, filePath);
    }

    public boolean isActived(Context context){
        int code = FaceEngine.getActiveFileInfo(context, new ActiveFileInfo());
        return code == ErrorInfo.MOK;
    }
}
