package com.shensu.face.helper;

import com.shensu.face.camera.CompareResult;

public interface RecognizeListener {
    void onOpenFail(int failCode);

    void onRecognized(CompareResult compareResult);

    void onNoticeChanged(String notice);

    void onCompareSimilar(float similar,byte[] nv21);
}
