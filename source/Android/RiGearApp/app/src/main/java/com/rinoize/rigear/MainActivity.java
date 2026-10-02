package com.rinoize.rigear;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK_ROM = 1001;
    private static final int MAX_ROM_BYTES = 16 * 1024 * 1024;

    private final ExecutorService nativeExecutor = Executors.newSingleThreadExecutor();

    private TextView statusView;
    private TextView detailsView;
    private Button loadRomButton;
    private Button selfTestButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 28, 48, 28);

        TextView title = new TextView(this);
        title.setText("RiGear 0.2 Virus Boot Test");
        title.setTextSize(27f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        statusView = new TextView(this);
        statusView.setTextSize(21f);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, 22, 0, 10);

        detailsView = new TextView(this);
        detailsView.setTextSize(16f);
        detailsView.setGravity(Gravity.CENTER);
        detailsView.setPadding(0, 0, 0, 22);

        loadRomButton = new Button(this);
        loadRomButton.setText("LOAD VIRUS A/B/C ROM (.BIN)");
        loadRomButton.setOnClickListener(v -> pickRom());

        selfTestButton = new Button(this);
        selfTestButton.setText("RUN NATIVE SELF TEST");
        selfTestButton.setOnClickListener(v -> runNativeSelfTest());

        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(detailsView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(loadRomButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(selfTestButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        runNativeSelfTest();
    }

    private void runNativeSelfTest() {
        try {
            final String info = NativeBridge.nativeGetCoreInfo();
            final int result = NativeBridge.nativeSelfTest();

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

    private void pickRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_ROM);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_ROM || resultCode != RESULT_OK || data == null)
            return;

        final Uri uri = data.getData();
        if (uri == null)
            return;

        final String name = getDisplayName(uri);
        setBusy(true);
        statusView.setText("ROM: " + name + "\nValidating and booting DSP...");
        detailsView.setText("This can take several seconds on first boot.");

        nativeExecutor.execute(() -> {
            try {
                byte[] bytes = readUri(uri);
                final String result = NativeBridge.nativeLoadRom(bytes, name);

                runOnUiThread(() -> {
                    statusView.setText("RiGear / OSIRUS");
                    detailsView.setText(result);
                    setBusy(false);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    statusView.setText("ROM LOAD: FAILED");
                    detailsView.setText(t.getClass().getSimpleName() + ": " + t.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private byte[] readUri(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null)
                throw new IllegalStateException("Android could not open the selected file.");

            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > MAX_ROM_BYTES)
                    throw new IllegalArgumentException("Selected file is larger than 16 MB.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String getDisplayName(Uri uri) {
        String result = "virus.bin";
        try (Cursor cursor = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty())
                        result = value;
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private void setBusy(boolean busy) {
        loadRomButton.setEnabled(!busy);
        selfTestButton.setEnabled(!busy);
    }

    @Override
    protected void onDestroy() {
        nativeExecutor.shutdown();
        super.onDestroy();
    }
}
