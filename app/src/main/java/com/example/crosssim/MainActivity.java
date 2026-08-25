package com.example.crosssim;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.InvocationTargetException;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class MainActivity extends Activity {

    private static final int SHIZUKU_PERMISSION_REQUEST = 1001;

    private EditText subIdInput;
    private TextView shizukuStatus;
    private TextView resultView;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshShizukuStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
            refreshShizukuStatus();
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                toast("Shizuku 已授权");
            } else {
                toast("Shizuku 授权被拒绝");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This project intentionally uses non-SDK telephony interfaces.
        // HiddenApiBypass keeps reflection usable on Android 9+.
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Lcom/android/internal/telephony/"
            );
        } catch (Throwable ignored) {
        }

        setContentView(buildUi());

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        refreshShizukuStatus();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    private View buildUi() {
        int pad = dp(20);
        int gap = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Cross-SIM Calling · Shizuku");
        title.setTextSize(22);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText("用于调用 Android ITelephony 的用户级 Cross-SIM 开关。你的 T-Mobile 当前 subId 是 15。\n\n注意：这不会修改 CarrierConfig；请保留 TurboIMS 中 T-Mobile 的 Cross-SIM / VoWiFi 配置为开启。");
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        noteLp.topMargin = gap;
        root.addView(note, noteLp);

        shizukuStatus = new TextView(this);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusLp.topMargin = gap;
        root.addView(shizukuStatus, statusLp);

        Button requestPermission = new Button(this);
        requestPermission.setText("请求 Shizuku 权限");
        requestPermission.setOnClickListener(v -> requestShizukuPermission());
        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnLp1.topMargin = gap;
        root.addView(requestPermission, btnLp1);

        subIdInput = new EditText(this);
        subIdInput.setHint("T-Mobile subId");
        subIdInput.setText("15");
        subIdInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputLp.topMargin = gap;
        root.addView(subIdInput, inputLp);

        Button read = new Button(this);
        read.setText("读取 Cross-SIM 状态");
        read.setOnClickListener(v -> runTelephonyAction(Action.READ));
        LinearLayout.LayoutParams readLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        readLp.topMargin = gap;
        root.addView(read, readLp);

        Button enable = new Button(this);
        enable.setText("开启 Cross-SIM");
        enable.setOnClickListener(v -> runTelephonyAction(Action.ENABLE));
        LinearLayout.LayoutParams enableLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        enableLp.topMargin = gap;
        root.addView(enable, enableLp);

        Button disable = new Button(this);
        disable.setText("关闭 Cross-SIM");
        disable.setOnClickListener(v -> runTelephonyAction(Action.DISABLE));
        LinearLayout.LayoutParams disableLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        disableLp.topMargin = gap;
        root.addView(disable, disableLp);

        resultView = new TextView(this);
        resultView.setTextIsSelectable(true);
        resultView.setText("等待操作…");
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        resultLp.topMargin = gap;
        root.addView(resultView, resultLp);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            toast("Shizuku 未运行，请先启动 Shizuku");
            return;
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            toast("已经拥有 Shizuku 权限");
            refreshShizukuStatus();
            return;
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            toast("请在 Shizuku 中允许此应用");
        }
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
    }

    private void refreshShizukuStatus() {
        runOnUiThread(() -> {
            if (!Shizuku.pingBinder()) {
                shizukuStatus.setText("Shizuku：未运行 / Binder 不可用");
                return;
            }

            int permission = Shizuku.checkSelfPermission();
            String permissionText = permission == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权";
            try {
                int uid = Shizuku.getUid();
                String mode = uid == 0 ? "ROOT" : (uid == 2000 ? "ADB shell" : "UID " + uid);
                shizukuStatus.setText("Shizuku：运行中 · " + permissionText + " · " + mode);
            } catch (Throwable t) {
                shizukuStatus.setText("Shizuku：运行中 · " + permissionText);
            }
        });
    }

    private void runTelephonyAction(Action action) {
        if (!ensureShizukuReady()) return;

        final int subId;
        try {
            subId = Integer.parseInt(subIdInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("subId 无效");
            return;
        }

        resultView.setText("执行中…");

        new Thread(() -> {
            try {
                Object telephony = getTelephonyViaShizuku();
                Class<?> iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony");

                if (action == Action.ENABLE || action == Action.DISABLE) {
                    boolean enabled = action == Action.ENABLE;
                    HiddenApiBypass.invoke(
                            iTelephonyClass,
                            telephony,
                            "setCrossSimCallingEnabled",
                            subId,
                            enabled
                    );

                    // Read back from the same privileged Binder call.
                    Object value = HiddenApiBypass.invoke(
                            iTelephonyClass,
                            telephony,
                            "isCrossSimCallingEnabledByUser",
                            subId
                    );

                    showResult(
                            "调用成功\n" +
                            "subId = " + subId + "\n" +
                            "setCrossSimCallingEnabled(" + enabled + ")\n" +
                            "读取回传 = " + value + "\n\n" +
                            "接着用 ADB 验证：\n" +
                            "adb shell \"dumpsys isub | grep 'id=" + subId + " ' | grep -o 'crossSimCallingEnabled=[0-9]'\""
                    );
                } else {
                    Object value = HiddenApiBypass.invoke(
                            iTelephonyClass,
                            telephony,
                            "isCrossSimCallingEnabledByUser",
                            subId
                    );
                    showResult("subId = " + subId + "\nCross-SIM 用户开关 = " + value);
                }
            } catch (Throwable t) {
                showResult("调用失败\n\n" + describeThrowable(t));
            }
        }, "cross-sim-call").start();
    }

    private Object getTelephonyViaShizuku() throws Exception {
        IBinder rawPhoneBinder = SystemServiceHelper.getSystemService("phone");
        if (rawPhoneBinder == null) {
            throw new IllegalStateException("找不到 phone Binder service");
        }

        // The wrapped Binder forwards transactions through the Shizuku server,
        // so Android sees the remote caller as the Shizuku server (ADB shell/root).
        IBinder privilegedBinder = new ShizukuBinderWrapper(rawPhoneBinder);

        Class<?> stubClass = Class.forName("com.android.internal.telephony.ITelephony$Stub");
        Object telephony = HiddenApiBypass.invoke(
                stubClass,
                null,
                "asInterface",
                privilegedBinder
        );

        if (telephony == null) {
            throw new IllegalStateException("ITelephony.Stub.asInterface 返回 null");
        }
        return telephony;
    }

    private boolean ensureShizukuReady() {
        if (!Shizuku.pingBinder()) {
            toast("Shizuku 未运行");
            return false;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestShizukuPermission();
            return false;
        }
        return true;
    }

    private void showResult(String text) {
        runOnUiThread(() -> resultView.setText(text));
    }

    private String describeThrowable(Throwable t) {
        Throwable x = t;
        if (x instanceof InvocationTargetException && ((InvocationTargetException) x).getTargetException() != null) {
            x = ((InvocationTargetException) x).getTargetException();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(x.getClass().getName());
        if (x.getMessage() != null) sb.append(": ").append(x.getMessage());

        sb.append("\n\n常见原因：\n")
                .append("1. Shizuku 没有授权或不是 ADB/ROOT 模式\n")
                .append("2. OriginOS 修改了 ITelephony 接口\n")
                .append("3. vivo 的 Telephony 服务额外限制了 shell UID\n")
                .append("4. 当前系统不存在 setCrossSimCallingEnabled 方法\n\n")
                .append("完整异常：\n");

        for (StackTraceElement e : x.getStackTrace()) {
            sb.append("  at ").append(e).append('\n');
            if (sb.length() > 6000) break;
        }
        return sb.toString();
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum Action {
        READ,
        ENABLE,
        DISABLE
    }
}
