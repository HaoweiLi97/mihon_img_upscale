package dev.mihon.npubench;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String MODEL = "realcugan-pro-x2-denoise3x-fp16.dlc";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status, timer, output;
    private Button coldButton, warmButton;
    private volatile boolean running;
    private long started;
    private String history = "";
    private File contextFile, resultFile, markerFile;
    private String nativeLoadError;

    private native String nativeRun(String model, String context, String libraries, boolean cold);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        contextFile = new File(getFilesDir(), "realcugan-pro-x2-denoise3x-fp16.context.bin");
        resultFile = new File(getFilesDir(), "last-result.txt");
        markerFile = new File(getFilesDir(), "running-stage.txt");
        try { System.loadLibrary("npubench"); } catch (Throwable e) { nativeLoadError = e.toString(); }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad, pad, pad);
        body.setBackgroundColor(Color.rgb(245, 247, 250));
        body.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
            return insets;
        });
        body.addView(label("NPU Context 编译测试", 25));
        body.addView(label("Real-CUGAN Pro 2x · denoise3x · FP16\n输入 256×256 → 输出 512×512 · QNN 2.49\n仅在本机编译，不需要联网。", 16));
        status = label("就绪：点击下方按钮开始，测试期间请保持此页面打开。", 16);
        body.addView(status);
        timer = label("尚未运行", 22);
        body.addView(timer);
        coldButton = new Button(this);
        coldButton.setText("开始 / 重新编译 Context");
        coldButton.setOnClickListener(v -> start(true));
        body.addView(coldButton);
        warmButton = new Button(this);
        warmButton.setText("测试已缓存 Context 的加载");
        warmButton.setEnabled(contextFile.isFile());
        warmButton.setOnClickListener(v -> start(false));
        body.addView(warmButton);
        Button copy = new Button(this);
        copy.setText("复制测试日志");
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("NPU benchmark", output.getText()));
        });
        body.addView(copy);
        output = label("编译后会自动加载 context 并执行一个合成图块，确认它可用。\n首次编译不包含下载或模型解包时间。", 14);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(body);
        if (resultFile.isFile()) {
            try { history = new String(Files.readAllBytes(resultFile.toPath()), StandardCharsets.UTF_8); output.setText(history); }
            catch (Exception ignored) {}
        }
        if (markerFile.isFile()) {
            try { status.setText("上次测试未正常结束，最后阶段：\n" + new String(Files.readAllBytes(markerFile.toPath()), StandardCharsets.UTF_8)); }
            catch (Exception ignored) {}
        }
        if (nativeLoadError != null) { status.setText(nativeLoadError); coldButton.setEnabled(false); warmButton.setEnabled(false); }
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(24, 37, 55));
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    public void onNativeStage(String stage) {
        String line = String.format(Locale.US, "[%.1f s] %s", (SystemClock.elapsedRealtime() - started) / 1000.0, stage);
        android.util.Log.i("NpuBench", line);
        try { write(markerFile, line); } catch (Exception ignored) {}
        handler.post(() -> { status.setText(stage); output.append(line + "\n"); });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            timer.setText(String.format(Locale.US, "已用时 %.1f 秒", (SystemClock.elapsedRealtime() - started) / 1000.0));
            handler.postDelayed(this, 200);
        }
    };

    private void start(boolean cold) {
        if (running) return;
        running = true;
        coldButton.setEnabled(false); warmButton.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        started = SystemClock.elapsedRealtime();
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        String header = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()) + "\n"
            + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.HARDWARE
            + (Build.VERSION.SDK_INT >= 31 ? " / " + Build.SOC_MODEL : "") + "\n"
            + "Android " + Build.VERSION.RELEASE + " / " + Build.SUPPORTED_ABIS[0] + "\n"
            + "Available RAM: " + memory.availMem / 1048576 + " MiB\n"
            + "Model: " + MODEL + "\nMode: " + (cold ? "COLD COMPILE" : "CACHED LOAD") + "\n\n";
        output.setText(header);
        handler.post(tick);
        executor.execute(() -> {
            String result;
            try {
                onNativeStage("Preparing model file (excluded from native compile timing)");
                File dlc = new File(getFilesDir(), MODEL);
                if (!dlc.isFile()) {
                    File temporary = new File(getFilesDir(), MODEL + ".partial");
                    try (InputStream input = getAssets().open(MODEL); FileOutputStream out = new FileOutputStream(temporary)) {
                        byte[] buffer = new byte[1024 * 1024]; int count;
                        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                    if (!temporary.renameTo(dlc)) throw new IllegalStateException("Unable to save model");
                }
                // Always remove the saved binary before a cold run; the shared runtime otherwise reuses it.
                if (cold && contextFile.exists() && !contextFile.delete()) throw new IllegalStateException("Unable to remove old context");
                if (!cold && !contextFile.isFile()) throw new IllegalStateException("Please run cold compile first");
                result = nativeRun(dlc.getAbsolutePath(), contextFile.getAbsolutePath(), getApplicationInfo().nativeLibraryDir, cold);
            } catch (Throwable error) { result = "FAILED: " + error + "\n"; }
            final String finalResult = result;
            final double seconds = (SystemClock.elapsedRealtime() - started) / 1000.0;
            handler.post(() -> {
                running = false; handler.removeCallbacks(tick);
                timer.setText(String.format(Locale.US, "本次总耗时 %.1f 秒", seconds));
                status.setText(finalResult.contains("SUCCESS:") ? "测试成功，具体编译/加载耗时见下方。" : "测试失败，复制日志可查看原因。");
                output.append("\n" + finalResult);
                String previous = history;
                history = header + output.getText().toString().substring(header.length()) + "\nTotal wall time: " + seconds + " s\n\n" + previous;
                if (history.length() > 60000) history = history.substring(0, 60000);
                try { write(resultFile, history); } catch (Exception ignored) {}
                markerFile.delete();
                coldButton.setEnabled(true); warmButton.setEnabled(contextFile.isFile());
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        });
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(tick);
        executor.shutdown();
        super.onDestroy();
    }
}
