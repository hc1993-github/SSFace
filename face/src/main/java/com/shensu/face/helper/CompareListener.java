package com.shensu.face.helper;

public interface CompareListener {
    void onSimilarScore(float score);
    void onIdentifyRecognize(boolean isSuccess);
    void onPhotoRecognize(boolean isSuccess);
    void onImageQualityLow(boolean isMainImage);
    void onWearMask(boolean isMainImage);
}
