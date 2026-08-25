package com.example.crosssim;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class MainActivity extends Activity {

    private static final int SHIZUKU_PERMISSION_REQUEST = 1001;
    private static final int PHONE_STATE_PERMISSION_REQUEST = 1002;
    private static final String SETTINGS_NAME = "cross_sim_settings";
    private static final String SUB_ID_KEY = "selected_sub_id";
    private static final String CARRIER_CROSS_SIM_AVAILABLE_KEY =
            "carrier_cross_sim_ims_available_bool";
    private static final String CROSS_SIM_ON_OPPORTUNISTIC_DATA_KEY =
            "enable_cross_sim_calling_on_opportunistic_data_bool";

    private EditText subIdInput;
    private TextView shizukuStatus;
    private TextView subscriptionsStatus;
    private LinearLayout subscriptionsContainer;
    private AlertDialog progressDialog;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshShizukuStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;

        refreshShizukuStatus();
        showNotice(grantResult == PackageManager.PERMISSION_GRANTED
                ? "Shizuku 已授权"
                : "Shizuku 授权被拒绝");
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // This project intentionally uses non-SDK telephony interfaces.
        try {
            HiddenApiBypass.addHiddenApiExemptions("Lcom/android/internal/telephony/");
        } catch (Throwable ignored) {
            // The actual call below will show a detailed error if bypassing is unavailable.
        }

        setContentView(buildUi());

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        refreshShizukuStatus();
        refreshSubscriptionChoices(false);
    }

    @Override
    protected void onPause() {
        saveSubId();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissProgressDialog();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PHONE_STATE_PERMISSION_REQUEST) return;

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshSubscriptionChoices(false);
        } else {
            subscriptionsStatus.setText("未授予电话权限；仍可手动输入 subId。");
        }
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
        note.setText("读取或修改 Android 的用户级 Cross-SIM Calling 开关。"
                + "适用于不同机型、运营商和 SIM 卡；实际支持情况取决于系统电话服务与运营商配置。");
        addWithTopMargin(root, note, gap);

        shizukuStatus = new TextView(this);
        addWithTopMargin(root, shizukuStatus, gap);

        Button requestPermission = createButton("请求 Shizuku 权限");
        requestPermission.setOnClickListener(v -> requestShizukuPermission());
        addWithTopMargin(root, requestPermission, gap);

        TextView subscriptionTitle = new TextView(this);
        subscriptionTitle.setText("选择 SIM");
        subscriptionTitle.setTextSize(18);
        addWithTopMargin(root, subscriptionTitle, dp(20));

        subscriptionsStatus = new TextView(this);
        addWithTopMargin(root, subscriptionsStatus, dp(8));

        subscriptionsContainer = new LinearLayout(this);
        subscriptionsContainer.setOrientation(LinearLayout.VERTICAL);
        addWithTopMargin(root, subscriptionsContainer, dp(4));

        Button discoverSubscriptions = createButton("检测当前 SIM");
        discoverSubscriptions.setOnClickListener(v -> refreshSubscriptionChoices(true));
        addWithTopMargin(root, discoverSubscriptions, dp(8));

        subIdInput = new EditText(this);
        subIdInput.setHint("输入 subId，或从上方 SIM 列表选择");
        subIdInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        subIdInput.setText(getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE).getString(SUB_ID_KEY, ""));
        addWithTopMargin(root, subIdInput, gap);

        Button read = createButton("读取 Cross-SIM 状态");
        read.setOnClickListener(v -> runTelephonyAction(Action.READ));
        addWithTopMargin(root, read, gap);

        Button enable = createButton("开启 Cross-SIM");
        enable.setOnClickListener(v -> runTelephonyAction(Action.ENABLE));
        addWithTopMargin(root, enable, gap);

        Button disable = createButton("关闭 Cross-SIM");
        disable.setOnClickListener(v -> runTelephonyAction(Action.DISABLE));
        addWithTopMargin(root, disable, gap);

        TextView persistenceTitle = new TextView(this);
        persistenceTitle.setText("持久化 CarrierConfig");
        persistenceTitle.setTextSize(18);
        addWithTopMargin(root, persistenceTitle, dp(20));

        TextView persistenceNote = new TextView(this);
        persistenceNote.setText("将两个 Cross-SIM CarrierConfig 测试覆盖写入系统并跨重启保留。"
                + "这是独立于用户级开关的高级操作，厂商系统可能不支持。");
        addWithTopMargin(root, persistenceNote, dp(8));

        Button persistCarrierConfig = createButton("持久化 Cross-SIM CarrierConfig");
        persistCarrierConfig.setOnClickListener(v -> persistCrossSimCarrierConfig());
        addWithTopMargin(root, persistCarrierConfig, gap);

        Button enableAndPersist = createButton("一键开启并持久化");
        enableAndPersist.setOnClickListener(v -> enableAndPersistAll());
        addWithTopMargin(root, enableAndPersist, gap);

        Button clearPersistentConfig = createButton("清除该 SIM 的全部 CarrierConfig 覆盖");
        clearPersistentConfig.setOnClickListener(v -> confirmClearPersistentCarrierConfig());
        addWithTopMargin(root, clearPersistentConfig, gap);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private Button createButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private void addWithTopMargin(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = topMargin;
        parent.addView(child, params);
    }

    private void requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            showNotice("Shizuku 未运行，请先启动 Shizuku");
            return;
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            showNotice("已经拥有 Shizuku 权限");
            refreshShizukuStatus();
            return;
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            showNotice("请在 Shizuku 中允许此应用");
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
            } catch (Throwable ignored) {
                shizukuStatus.setText("Shizuku：运行中 · " + permissionText);
            }
        });
    }

    private void refreshSubscriptionChoices(boolean requestPermissionIfNeeded) {
        subscriptionsContainer.removeAllViews();

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            subscriptionsStatus.setText("允许电话权限可自动发现 subId；此权限不是执行 Cross-SIM 操作的必要条件。");
            if (requestPermissionIfNeeded) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_PHONE_STATE},
                        PHONE_STATE_PERMISSION_REQUEST
                );
            }
            return;
        }

        try {
            SubscriptionManager manager = getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> subscriptions = manager == null
                    ? null
                    : manager.getActiveSubscriptionInfoList();

            if (subscriptions == null || subscriptions.isEmpty()) {
                subscriptionsStatus.setText("没有发现可见的活动 SIM；请手动输入 subId。");
                return;
            }

            subscriptionsStatus.setText("发现 " + subscriptions.size() + " 张活动 SIM，点按一项选择：");
            for (SubscriptionInfo info : subscriptions) {
                int subId = info.getSubscriptionId();
                int slotIndex = info.getSimSlotIndex();
                CharSequence displayName = info.getDisplayName();
                String slot = slotIndex >= 0 ? "SIM " + (slotIndex + 1) : "SIM";
                String name = displayName == null || displayName.length() == 0
                        ? "未命名"
                        : displayName.toString();

                Button choice = createButton(slot + " · " + name + "\nsubId = " + subId);
                choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                choice.setOnClickListener(v -> selectSubId(subId));
                addWithTopMargin(subscriptionsContainer, choice, dp(6));
            }
        } catch (SecurityException e) {
            subscriptionsStatus.setText("系统拒绝读取 SIM 信息；请手动输入 subId。");
        } catch (UnsupportedOperationException e) {
            subscriptionsStatus.setText("设备不支持订阅信息 API；请手动输入 subId。");
        }
    }

    private void selectSubId(int subId) {
        subIdInput.setText(String.valueOf(subId));
        subIdInput.setSelection(subIdInput.length());
        saveSubId();
        showNotice("已选择 subId " + subId);
    }

    private void saveSubId() {
        if (subIdInput == null) return;

        String value = subIdInput.getText().toString().trim();
        getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
                .edit()
                .putString(SUB_ID_KEY, value)
                .apply();
    }

    private Integer readSelectedSubId() {
        try {
            int subId = Integer.parseInt(subIdInput.getText().toString().trim());
            if (!SubscriptionManager.isValidSubscriptionId(subId)) {
                throw new NumberFormatException("invalid subscription ID");
            }
            saveSubId();
            return subId;
        } catch (NumberFormatException e) {
            showNotice("请输入有效的 subId");
            return null;
        }
    }

    private void runTelephonyAction(Action action) {
        if (!ensureShizukuReady()) return;

        Integer selectedSubId = readSelectedSubId();
        if (selectedSubId == null) return;
        final int subId = selectedSubId;
        showProgress("正在执行 Cross-SIM 操作…");

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

                    if (enabled) {
                        overrideCarrierConfigViaShizuku(
                                subId,
                                createCrossSimOverrides(),
                                false
                        );
                    }

                    Object value = HiddenApiBypass.invoke(
                            iTelephonyClass,
                            telephony,
                            "isCrossSimCallingEnabledByUser",
                            subId
                    );

                    showResult(
                            enabled ? "开启成功" : "关闭成功",
                            "调用成功\n"
                                    + "subId = " + subId + "\n"
                                    + "setCrossSimCallingEnabled(" + enabled + ")\n"
                                    + "读取回传 = " + value + "\n\n"
                                    + (enabled
                                            ? "已写入普通 CarrierConfig（persistent = false）\n\n"
                                            : "")
                                    + "可继续通过 adb shell dumpsys isub 验证系统记录。"
                    );
                } else {
                    Object value = HiddenApiBypass.invoke(
                            iTelephonyClass,
                            telephony,
                            "isCrossSimCallingEnabledByUser",
                            subId
                    );
                    showResult(
                            "读取完成",
                            "subId = " + subId + "\nCross-SIM 用户开关 = " + value
                    );
                }
            } catch (Throwable t) {
                String actionName = action == Action.ENABLE
                        ? "开启"
                        : (action == Action.DISABLE ? "关闭" : "读取");
                showResult(actionName + "失败", describeThrowable(t));
            }
        }, "cross-sim-call").start();
    }

    private void persistCrossSimCarrierConfig() {
        if (!ensureShizukuReady()) return;

        Integer selectedSubId = readSelectedSubId();
        if (selectedSubId == null) return;
        final int subId = selectedSubId;

        showProgress("正在提交持久化 CarrierConfig 覆盖…");
        new Thread(() -> {
            try {
                overrideCarrierConfigViaShizuku(subId, createCrossSimOverrides(), true);
                showResult(
                        "持久化请求已提交",
                        "持久化请求已提交\n"
                                + "subId = " + subId + "\n"
                                + CARRIER_CROSS_SIM_AVAILABLE_KEY + " = true\n"
                                + CROSS_SIM_ON_OPPORTUNISTIC_DATA_KEY + " = true\n"
                                + "persistent = true\n\n"
                                + "建议等待电话服务刷新，并在重启后再次验证。"
                );
            } catch (Throwable t) {
                showResult("持久化失败", describeThrowable(t));
            }
        }, "persist-carrier-config").start();
    }

    private void enableAndPersistAll() {
        if (!ensureShizukuReady()) return;

        Integer selectedSubId = readSelectedSubId();
        if (selectedSubId == null) return;
        final int subId = selectedSubId;

        showProgress("正在开启用户开关并提交持久化覆盖…");
        new Thread(() -> {
            try {
                Object telephony = getTelephonyViaShizuku();
                Class<?> iTelephonyClass = Class.forName("com.android.internal.telephony.ITelephony");
                HiddenApiBypass.invoke(
                        iTelephonyClass,
                        telephony,
                        "setCrossSimCallingEnabled",
                        subId,
                        true
                );

                overrideCarrierConfigViaShizuku(subId, createCrossSimOverrides(), true);

                Object value = HiddenApiBypass.invoke(
                        iTelephonyClass,
                        telephony,
                        "isCrossSimCallingEnabledByUser",
                        subId
                );

                showResult(
                        "一键操作已提交",
                        "一键操作已提交\n"
                                + "subId = " + subId + "\n"
                                + "Cross-SIM 用户开关 = " + value + "\n"
                                + "CarrierConfig persistent = true\n\n"
                                + "CarrierConfig 写入由电话服务异步处理，建议稍后及重启后验证。"
                );
            } catch (Throwable t) {
                showResult("一键操作失败", describeThrowable(t));
            }
        }, "enable-persist-all").start();
    }

    private void confirmClearPersistentCarrierConfig() {
        if (!ensureShizukuReady()) return;

        Integer subId = readSelectedSubId();
        if (subId == null) return;

        new AlertDialog.Builder(this)
                .setTitle("清除全部 CarrierConfig 覆盖？")
                .setMessage("这会清除 subId " + subId
                        + " 的全部测试覆盖，包括其他工具写入的项目，并恢复运营商生产配置。"
                        + " 此操作不能只删除本应用写入的两个键。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续清除", (dialog, which) -> clearPersistentCarrierConfig(subId))
                .show();
    }

    private void clearPersistentCarrierConfig(int subId) {
        if (!ensureShizukuReady()) return;

        showProgress("正在清除 CarrierConfig 覆盖…");
        new Thread(() -> {
            try {
                overrideCarrierConfigViaShizuku(subId, null, true);
                showResult(
                        "清除请求已提交",
                        "清除请求已提交\n"
                                + "subId = " + subId + "\n\n"
                                + "该订阅的全部测试 CarrierConfig 覆盖将被移除，并恢复生产配置。"
                );
            } catch (Throwable t) {
                showResult("清除失败", describeThrowable(t));
            }
        }, "clear-carrier-config").start();
    }

    private PersistableBundle createCrossSimOverrides() {
        PersistableBundle overrides = new PersistableBundle();
        overrides.putBoolean(CARRIER_CROSS_SIM_AVAILABLE_KEY, true);
        overrides.putBoolean(CROSS_SIM_ON_OPPORTUNISTIC_DATA_KEY, true);
        return overrides;
    }

    private void overrideCarrierConfigViaShizuku(
            int subId,
            PersistableBundle overrides,
            boolean persistent
    ) throws Exception {
        IBinder rawCarrierConfigBinder = SystemServiceHelper.getSystemService("carrier_config");
        if (rawCarrierConfigBinder == null) {
            throw new IllegalStateException("找不到 carrier_config Binder service");
        }

        IBinder privilegedBinder = new ShizukuBinderWrapper(rawCarrierConfigBinder);
        Class<?> stubClass = Class.forName(
                "com.android.internal.telephony.ICarrierConfigLoader$Stub"
        );
        Object loader = HiddenApiBypass.invoke(stubClass, null, "asInterface", privilegedBinder);
        if (loader == null) {
            throw new IllegalStateException("ICarrierConfigLoader.Stub.asInterface 返回 null");
        }

        Class<?> loaderInterface = Class.forName(
                "com.android.internal.telephony.ICarrierConfigLoader"
        );
        loaderInterface.getMethod(
                "overrideConfig",
                int.class,
                PersistableBundle.class,
                boolean.class
        ).invoke(loader, subId, overrides, persistent);
    }

    private Object getTelephonyViaShizuku() throws Exception {
        IBinder rawPhoneBinder = SystemServiceHelper.getSystemService("phone");
        if (rawPhoneBinder == null) {
            throw new IllegalStateException("找不到 phone Binder service");
        }

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
            showNotice("Shizuku 未运行");
            return false;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestShizukuPermission();
            return false;
        }
        return true;
    }

    private void showResult(String title, String text) {
        showDialog(title, text, true);
    }

    private void showProgress(String text) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            dismissProgressDialog();
            progressDialog = new AlertDialog.Builder(this)
                    .setTitle("正在处理")
                    .setMessage(text)
                    .setCancelable(true)
                    .create();
            progressDialog.show();
        });
    }

    private void showNotice(String text) {
        showDialog("提示", text, false);
    }

    private void showDialog(String title, String text, boolean dismissProgress) {
        runOnUiThread(() -> {
            if (dismissProgress) {
                dismissProgressDialog();
            }
            if (isFinishing() || isDestroyed()) return;

            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(text)
                    .setPositiveButton("确定", null)
                    .show();
        });
    }

    private void dismissProgressDialog() {
        if (progressDialog == null) return;
        progressDialog.dismiss();
        progressDialog = null;
    }

    private String describeThrowable(Throwable throwable) {
        Throwable error = throwable;
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getTargetException() != null) {
            error = ((InvocationTargetException) error).getTargetException();
        }

        StringBuilder description = new StringBuilder();
        description.append(error.getClass().getName());
        if (error.getMessage() != null) {
            description.append(": ").append(error.getMessage());
        }

        description.append("\n\n常见原因：\n")
                .append("1. Shizuku 未授权，或服务不是 ADB/ROOT 模式\n")
                .append("2. 厂商修改或移除了 ITelephony / ICarrierConfigLoader 接口\n")
                .append("3. 电话服务拒绝 shell UID 使用 MODIFY_PHONE_STATE 能力\n")
                .append("4. 当前系统或运营商不支持对应的 Cross-SIM 配置\n\n")
                .append("完整异常：\n");

        for (StackTraceElement element : error.getStackTrace()) {
            description.append("  at ").append(element).append('\n');
            if (description.length() > 6000) break;
        }
        return description.toString();
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
