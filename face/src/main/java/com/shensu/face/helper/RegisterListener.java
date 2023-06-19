package com.shensu.face.helper;

public interface RegisterListener {
    void onRegisterFinished(boolean isSuccess);
    void onMultiRegisterProcess(int current, int failed, int total);
    void onMultiRegisterFinish(int current, int failed, int total, String errMsg);
}
