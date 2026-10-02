package com.rinoize.rigear;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView detailsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 32, 48, 32);

        TextView title = new TextView(this);
        title.setText("RiGear 0.1 Diagnostic");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        statusView = new TextView(this);
        statusView.setTextSize(22f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 28, 0, 12);

        detailsView = new TextView(this);
        detailsView.setTextSize(16f);
        detailsView.setGravity(Gravity.CENTER);
        detailsView.setPadding(0, 0, 0, 24);

        Button testButton = new Button(this);
        testButton.setText("RUN NATIVE SELF TEST");
        testButton.setOnClickListener(v -> runNativeTest());

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(detailsView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(testButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        runNativeTest();
    }

    private void runNativeTest() {
        try {
            String info = NativeBridge.nativeGetCoreInfo();
            int result = NativeBridge.nativeSelfTest();

            if (result == 1) {
                statusView.setText("ARM64 CORE: OK\nJNI: OK");
            } else {
                statusView.setText("NATIVE SELF TEST: FAILED");
            }

            detailsView.setText(info);
        } catch (Throwable t) {
            statusView.setText("NATIVE LOAD: FAILED");
            detailsView.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
