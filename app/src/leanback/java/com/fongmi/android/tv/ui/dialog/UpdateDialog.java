package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogUpdateBinding;
import com.fongmi.android.tv.impl.UpdateListener;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class UpdateDialog extends BaseAlertDialog {

    private DialogUpdateBinding binding;
    private UpdateListener listener;
    private String title;
    private String desc;
    private int sourceAttempt;
    private int sourceCount;

    public static UpdateDialog create() {
        return new UpdateDialog();
    }

    public UpdateDialog title(String title) {
        this.title = title;
        return this;
    }

    public UpdateDialog desc(String desc) {
        this.desc = desc;
        return this;
    }

    public UpdateDialog listener(UpdateListener listener) {
        this.listener = listener;
        return this;
    }

    public UpdateDialog show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), "ota-update");
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogUpdateBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot()).setCancelable(true);
    }

    @Override
    protected void initView() {
        binding.version.setText(title);
        binding.desc.setText(desc);
        binding.confirm.post(() -> {
            if (binding.confirm.isShown() && binding.confirm.isEnabled()) binding.confirm.requestFocus();
        });
    }

    @Override
    protected void initEvent() {
        binding.confirm.setOnClickListener(this::onConfirm);
        binding.cancel.setOnClickListener(this::onCancel);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (listener == null) dismissAllowingStateLoss();
    }

    public void setProgress(int progress) {
        if (binding == null) return;
        binding.confirm.setText(getString(R.string.update_download_progress, sourceAttempt, sourceCount, progress));
    }

    public void setSource(int attempt, int total) {
        sourceAttempt = attempt;
        sourceCount = total;
        setProgress(0);
    }

    public void setVerifying() {
        if (binding != null) binding.confirm.setText(R.string.update_download_verifying);
    }

    private void onConfirm(View view) {
        if (binding.confirm.hasFocus() && binding.cancel.isShown() && binding.cancel.isEnabled()) binding.cancel.requestFocus();
        if (listener != null) listener.onConfirm(view);
    }

    private void onCancel(View view) {
        if (listener != null) listener.onCancel(view);
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        if (listener != null) listener.onCancel(null);
    }
}
