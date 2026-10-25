package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

/** Disposable host for source dialog instrumentation; does not load configured sources or accounts. */
public final class SpiderDialogPreviewActivity extends FragmentActivity {
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new FrameLayout(this));
    }
}
