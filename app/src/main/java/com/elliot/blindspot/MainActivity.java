package com.elliot.blindspot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * 主界面：单 Activity，与旧版 APK (debug12) 保持一致
 * 所有控制直接嵌入 activity_main.xml，无 Fragment 标签页
 */
public class MainActivity extends Activity {
    private static final String TAG = "TurningBlindSpot";
    private static final String PREFS_NAME = "turning_blind_spot_prefs";
    
    private static final String PREF_DISCLAIMER_ACCEPTED = "disclaimer_accepted";
    
    public static final String PREF_AUTO_START_ON_BOOT = "auto_start_on_boot";
    public static final String PREF_SHOW_FLOATING_WINDOW = "show_floating_window";
    public static final String PREF_FLOATING_WINDOW_ROTATION = "floating_window_rotation";
    public static final String PREF_FLOATING_WINDOW_ROTATION_LEFT = "floating_window_rotation_left";
    public static final String PREF_FLOATING_WINDOW_ROTATION_RIGHT = "floating_window_rotation_right";
    public static final String PREF_TARGET_DISPLAY_ID = "target_display_id";
    public static final String PREF_BRIGHTNESS = "brightness";
    public static final String PREF_CONTRAST = "contrast";
    public static final String PREF_WHITE_BALANCE = "white_balance";
    public static final String PREF_DISPLAY_DELAY = "display_delay";
    private static final String PREF_SHOW_INDICATOR = "show_indicator";
    public static final String PREF_CAMERA_ID = "camera_id";
    public static final String PREF_TURN_KEYWORD_LEFT = "turn_keyword_left";
    public static final String PREF_TURN_KEYWORD_RIGHT = "turn_keyword_right";
    public static final String PREF_TURN_KEYWORD_PRESET = "turn_keyword_preset";
    public static final String PREF_TURN_AUTO_CLOSE_TIMEOUT_SEC = "turn_auto_close_timeout_sec";
    public static final String PREF_DEBUG_FILE_LOGGING = "debug_file_logging";

    private static final String TURN_KEYWORD_PRESET_CUSTOM = "custom";
    private static final String TURN_KEYWORD_PRESET_SCHEME_1 = "scheme_1";
    private static final String TURN_KEYWORD_PRESET_SCHEME_2 = "scheme_2";

    private static final String TURN_KEYWORD_SCHEME_1_LEFT = "handleLightSignal, light changed, TL(1),TR(0)";
    private static final String TURN_KEYWORD_SCHEME_1_RIGHT = "handleLightSignal, light changed, TL(0),TR(1)";

    private static final String TURN_KEYWORD_SCHEME_2_LEFT = "handleLightSignal , light changed, TL(1),TR(0)";
    private static final String TURN_KEYWORD_SCHEME_2_RIGHT = "handleLightSignal , light changed, TL(0),TR(1)";
    
    private static final long PERMISSION_CHECK_INTERVAL = 1000;
    
    // Views
    private TextView tvStatus;
    private TextView tvCameraPermission, tvCameraPermissionIcon;
    private TextView tvOverlayPermission, tvOverlayPermissionIcon;
    private TextView tvLogsPermission, tvLogsPermissionIcon;
    private TextView tvBrightnessValue, tvContrastValue, tvWhiteBalanceValue, tvDelayValue;
    private TextView tvTurnCloseTimeoutValue;
    private TextView tvPhotoStatus;
    
    private Button btnStartService, btnStopService;
    private Button btnPhotoToggle, btnPhotoOnce, btnPhotoCenter;
    private android.content.BroadcastReceiver photoStateReceiver;
    private String lastRecordPath;
    
    private Switch switchAutoStart, switchShowFloatingWindow, switchIndicator;
    
    private SeekBar seekbarBrightness, seekbarContrast, seekbarWhiteBalance, seekbarDelay;
    private SeekBar seekbarTurnCloseTimeout;
    private Spinner spinnerCameraId;
    private Button btnRefreshCameraIds;
    private ArrayAdapter<String> cameraIdAdapter;
    private boolean isInitializingCameraSpinner = false;

    private Spinner spinnerVehicleType;
    private View layoutE02Mapping;
    private View layoutCustomRegion;
    private Spinner spinnerMapFront;
    private Spinner spinnerMapBack;
    private Spinner spinnerMapLeft;
    private Spinner spinnerMapRight;
    private ArrayAdapter<String> segmentAdapter;
    private boolean isInitializingVehicleUi = false;
    private TextView tvCameraResolution;
    private Button btnEditCustomRegions;

    private Spinner spinnerTurnKeywordPreset;
    private EditText etTurnKeywordLeft;
    private EditText etTurnKeywordRight;
    private Button btnSaveTurnKeywords;
    private boolean isInitializingTurnKeywordPreset = false;

    private Spinner spinnerWindowRotationLeft;
    private Spinner spinnerWindowRotationRight;
    private boolean isInitializingWindowRotation = false;

    private EditText etLogDebugKeyword;
    private Button btnLogDebugMatch;
    private TextView tvLogDebugOutput;
    private volatile boolean isLogDebugRunning = false;
    private Switch switchFileLog;
    
    private SharedPreferences prefs;
    private Handler mainHandler;
    private Runnable permissionCheckRunnable;
    private boolean isResumed = false;
    private boolean isViewsInitialized = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        // 首次使用时强制显示安全警告
        if (!prefs.getBoolean(PREF_DISCLAIMER_ACCEPTED, false)) {
            showDisclaimerDialog();
            return; // 不初始化 UI，等用户同意后再继续
        }
        
        initAfterDisclaimer();
    }
    
    /** 用户同意免责声明后，初始化主界面 */
    private void initAfterDisclaimer() {
        initViews();
        isViewsInitialized = true;
        detectAndSaveDisplayId();
        updateUI();
        
        // 如果所有权限都已授予，自动启动服务
        if (hasAllPermissions() && prefs.getBoolean(PREF_AUTO_START_ON_BOOT, true)) {
            startTurningBlindSpotService();
        }
    }
    
    /** 首次使用时的安全免责声明弹窗 */
    private void showDisclaimerDialog() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int)(16 * density);
        
        // 外层 ScrollView
        ScrollView scrollView = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);
        scrollView.addView(container);
        
        TextView tvPreNotice = new TextView(this);
        tvPreNotice.setText("请先关注抖音@星星舰见 免费进群获取教程后再使用！");
        tvPreNotice.setTextSize(19);
        tvPreNotice.setTypeface(null, Typeface.BOLD);
        tvPreNotice.setTextColor(Color.parseColor("#D32F2F"));
        tvPreNotice.setPadding(0, 0, 0, (int)(12 * density));
        container.addView(tvPreNotice);

        // ===== 标题：严重安全警告 =====
        TextView tvTitle = new TextView(this);
        tvTitle.setText("⚠ 严重安全警告 - 请仔细阅读");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(Color.parseColor("#D32F2F"));
        tvTitle.setPadding(0, 0, 0, (int)(8 * density));
        container.addView(tvTitle);
        
        // ===== 红色醒目提示 =====
        TextView tvDanger = new TextView(this);
        tvDanger.setText("可能导致死亡或严重伤害！！！");
        tvDanger.setTextSize(16);
        tvDanger.setTypeface(null, Typeface.BOLD);
        tvDanger.setTextColor(Color.WHITE);
        tvDanger.setBackgroundColor(Color.parseColor("#D32F2F"));
        tvDanger.setPadding((int)(12 * density), (int)(8 * density), (int)(12 * density), (int)(8 * density));
        LinearLayout.LayoutParams dangerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dangerParams.bottomMargin = (int)(12 * density);
        tvDanger.setLayoutParams(dangerParams);
        container.addView(tvDanger);
        
        // ===== 正文 =====
        TextView tvBody = new TextView(this);
        tvBody.setTextSize(13);
        tvBody.setTextColor(Color.parseColor("#333333"));
        tvBody.setLineSpacing(0, 1.3f);
        tvBody.setText(
            "本软件为实验性开源项目，未经任何汽车功能安全认证，严禁用于实际驾驶决策。\n\n" +
            "技术风险包括但不限于：\n" +
            "• 视频流存在 200ms 以上延迟（受系统负载、编码性能影响）\n" +
            "• 画面可能出现卡顿、撕裂、色彩失真或完全中断\n" +
            "• 与车机系统资源竞争可能导致原厂安全功能（如倒车影像、ADAS）异常\n\n" +
            "法律责任声明：\n" +
            "• 本软件不能替代原厂后视镜、盲区监测（BSD）或任何法定安全设备；\n" +
            "• 禁止依据本软件画面判断车距、障碍物位置或进行变道/倒车操作；\n" +
            "• 开发者以\"现状\"（AS IS）提供本软件，不提供任何形式的明示或默示担保（包括但不限于适销性、特定用途适用性、安全性）；\n" +
            "• 因使用/安装本软件导致的任何人身伤害、财产损失或交通事故，开发者不承担法律责任；\n" +
            "• 用户自行承担安装、调试及使用过程中产生的全部风险，并确认已阅读本声明。"
        );
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.bottomMargin = (int)(16 * density);
        tvBody.setLayoutParams(bodyParams);
        container.addView(tvBody);
        
        // ===== 复选框 =====
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText("我已年满18周岁，充分理解上述风险，自愿承担全部责任，仅将本软件用于静止车辆的学习/调试目的。");
        checkBox.setTextSize(13);
        checkBox.setTextColor(Color.parseColor("#333333"));
        checkBox.setChecked(false);
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbParams.topMargin = (int)(8 * density);
        checkBox.setLayoutParams(cbParams);
        container.addView(checkBox);
        
        // ===== 构建对话框 =====
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scrollView)
                .setCancelable(false)
                .setPositiveButton("我同意并继续", null)  // 先设 null，后面覆盖以阻止自动关闭
                .setNegativeButton("不同意，退出", (d, which) -> {
                    finish();
                })
                .create();
        
        dialog.setOnShowListener(dialogInterface -> {
            Button btnAgree = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btnAgree.setEnabled(false);
            btnAgree.setTextColor(Color.GRAY);
            
            // 复选框勾选后才能点同意
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                btnAgree.setEnabled(isChecked);
                btnAgree.setTextColor(isChecked ? Color.parseColor("#1976D2") : Color.GRAY);
            });
            
            btnAgree.setOnClickListener(v -> {
                if (checkBox.isChecked()) {
                    prefs.edit().putBoolean(PREF_DISCLAIMER_ACCEPTED, true).apply();
                    dialog.dismiss();
                    initAfterDisclaimer();
                }
            });
            
            // 车机底部工具栏会遮挡对话框按钮，预留底部间距使「我同意并继续」可点击
            if (dialog.getWindow() != null) {
                int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.95f);
                int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.92f);
                dialog.getWindow().setLayout(w, h);
                int bottomDp = 88;
                int bottomPx = (int) (bottomDp * getResources().getDisplayMetrics().density);
                dialog.getWindow().getDecorView().setPadding(0, 0, 0, bottomPx);
            }
        });
        
        dialog.show();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        isResumed = true;
        if (!isViewsInitialized) return; // 免责声明弹窗显示中，Views 未初始化
        detectAndSaveDisplayId();
        updateUI();
        syncVehicleConfigFromPrefs();
        startPermissionCheck();
        if (photoStateReceiver != null) {
            android.content.IntentFilter f = new android.content.IntentFilter(VideoRecordController.ACTION_RECORD_STATE_CHANGED);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(photoStateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(photoStateReceiver, f);
            }
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        isResumed = false;
        if (!isViewsInitialized) return;
        if (photoStateReceiver != null) {
            try {
                unregisterReceiver(photoStateReceiver);
            } catch (Exception ignored) {
            }
        }
        stopPermissionCheck();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理 Handler 回调防止泄漏
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        stopPermissionCheck();
    }
    
    // ==================== 初始化 ====================
    
    private void detectAndSaveDisplayId() {
        Display display = getDisplay();
        if (display != null) {
            int displayId = display.getDisplayId();
            int width = display.getMode().getPhysicalWidth();
            int height = display.getMode().getPhysicalHeight();
            prefs.edit().putInt(PREF_TARGET_DISPLAY_ID, displayId).apply();
            Log.d(TAG, "Activity running on Display ID: " + displayId + " (" + width + "x" + height + ")");
        }
    }
    
    private void initViews() {
        // 版本号
        TextView tvVersion = findViewById(R.id.tv_version);
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            tvVersion.setText("v" + versionName);
        } catch (Exception e) {
            tvVersion.setText("v1.3.4-E02");
        }
        
        // 状态
        tvStatus = findViewById(R.id.tv_status);
        
        // 权限状态
        tvCameraPermission = findViewById(R.id.tv_camera_permission);
        tvCameraPermissionIcon = findViewById(R.id.tv_camera_permission_icon);
        tvOverlayPermission = findViewById(R.id.tv_overlay_permission);
        tvOverlayPermissionIcon = findViewById(R.id.tv_overlay_permission_icon);
        tvLogsPermission = findViewById(R.id.tv_battery_permission);
        tvLogsPermissionIcon = findViewById(R.id.tv_logs_permission_icon);
        
        // 按钮
        btnStartService = findViewById(R.id.btn_start_service);
        btnStopService = findViewById(R.id.btn_stop_service);
        btnPhotoToggle = findViewById(R.id.btn_photo_toggle);
        btnPhotoOnce = findViewById(R.id.btn_photo_once);
        btnPhotoCenter = findViewById(R.id.btn_photo_center);
        tvPhotoStatus = findViewById(R.id.tv_photo_status);
        
        // 开关
        switchAutoStart = findViewById(R.id.switch_auto_start);
        switchShowFloatingWindow = findViewById(R.id.switch_show_floating_window);
        switchIndicator = findViewById(R.id.switch_indicator);
        spinnerWindowRotationLeft = findViewById(R.id.spinner_window_rotation_left);
        spinnerWindowRotationRight = findViewById(R.id.spinner_window_rotation_right);
        
        // SeekBars
        seekbarBrightness = findViewById(R.id.seekbar_brightness);
        seekbarContrast = findViewById(R.id.seekbar_contrast);
        seekbarWhiteBalance = findViewById(R.id.seekbar_white_balance);
        seekbarDelay = findViewById(R.id.seekbar_delay);
        seekbarTurnCloseTimeout = findViewById(R.id.seekbar_turn_close_timeout);
        tvBrightnessValue = findViewById(R.id.tv_brightness_value);
        tvContrastValue = findViewById(R.id.tv_contrast_value);
        tvWhiteBalanceValue = findViewById(R.id.tv_white_balance_value);
        tvDelayValue = findViewById(R.id.tv_delay_value);
        tvTurnCloseTimeoutValue = findViewById(R.id.tv_turn_close_timeout_value);

        spinnerCameraId = findViewById(R.id.spinner_camera_id);
        btnRefreshCameraIds = findViewById(R.id.btn_refresh_camera_ids);
        btnRefreshCameraIds.setOnClickListener(v -> loadCameraIdList(true));
        loadCameraIdList(false);

        spinnerVehicleType = findViewById(R.id.spinner_vehicle_type);
        layoutE02Mapping = findViewById(R.id.layout_e02_mapping);
        layoutCustomRegion = findViewById(R.id.layout_custom_region);
        spinnerMapFront = findViewById(R.id.spinner_map_front);
        spinnerMapBack = findViewById(R.id.spinner_map_back);
        spinnerMapLeft = findViewById(R.id.spinner_map_left);
        spinnerMapRight = findViewById(R.id.spinner_map_right);
        tvCameraResolution = findViewById(R.id.tv_camera_resolution);
        btnEditCustomRegions = findViewById(R.id.btn_edit_custom_regions);
        btnEditCustomRegions.setOnClickListener(v -> {
            if (!TurningBlindSpotService.isRunning()) {
                Toast.makeText(this, "请先启动补盲服务", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, LayoutEditorActivity.class));
        });
        initVehicleConfigViews();

        spinnerTurnKeywordPreset = findViewById(R.id.spinner_turn_keyword_preset);
        etTurnKeywordLeft = findViewById(R.id.et_turn_keyword_left);
        etTurnKeywordRight = findViewById(R.id.et_turn_keyword_right);
        btnSaveTurnKeywords = findViewById(R.id.btn_save_turn_keywords);

        ArrayAdapter<String> turnKeywordPresetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        turnKeywordPresetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        turnKeywordPresetAdapter.add("自定义");
        turnKeywordPresetAdapter.add("方案1");
        turnKeywordPresetAdapter.add("星耀6选这个-方案2");
        spinnerTurnKeywordPreset.setAdapter(turnKeywordPresetAdapter);

        isInitializingTurnKeywordPreset = true;
        String savedPreset = prefs.getString(PREF_TURN_KEYWORD_PRESET, TURN_KEYWORD_PRESET_CUSTOM);
        boolean isScheme1 = TURN_KEYWORD_PRESET_SCHEME_1.equals(savedPreset);
        boolean isScheme2 = TURN_KEYWORD_PRESET_SCHEME_2.equals(savedPreset);
        
        if (isScheme1) {
            spinnerTurnKeywordPreset.setSelection(1);
        } else if (isScheme2) {
            spinnerTurnKeywordPreset.setSelection(2);
        } else {
            spinnerTurnKeywordPreset.setSelection(0);
        }

        if (isScheme1) {
            prefs.edit()
                    .putString(PREF_TURN_KEYWORD_LEFT, TURN_KEYWORD_SCHEME_1_LEFT)
                    .putString(PREF_TURN_KEYWORD_RIGHT, TURN_KEYWORD_SCHEME_1_RIGHT)
                    .apply();
        } else if (isScheme2) {
            prefs.edit()
                    .putString(PREF_TURN_KEYWORD_LEFT, TURN_KEYWORD_SCHEME_2_LEFT)
                    .putString(PREF_TURN_KEYWORD_RIGHT, TURN_KEYWORD_SCHEME_2_RIGHT)
                    .apply();
        }
        applyTurnKeywordPreset(savedPreset);
        isInitializingTurnKeywordPreset = false;

        spinnerTurnKeywordPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializingTurnKeywordPreset) return;
                
                String preset;
                String left = "";
                String right = "";
                
                if (position == 1) {
                    preset = TURN_KEYWORD_PRESET_SCHEME_1;
                    left = TURN_KEYWORD_SCHEME_1_LEFT;
                    right = TURN_KEYWORD_SCHEME_1_RIGHT;
                } else if (position == 2) {
                    preset = TURN_KEYWORD_PRESET_SCHEME_2;
                    left = TURN_KEYWORD_SCHEME_2_LEFT;
                    right = TURN_KEYWORD_SCHEME_2_RIGHT;
                } else {
                    preset = TURN_KEYWORD_PRESET_CUSTOM;
                }

                if (position != 0) {
                    prefs.edit()
                            .putString(PREF_TURN_KEYWORD_PRESET, preset)
                            .putString(PREF_TURN_KEYWORD_LEFT, left)
                            .putString(PREF_TURN_KEYWORD_RIGHT, right)
                            .apply();
                    notifySettingsChanged();
                    Toast.makeText(MainActivity.this, "已应用" + (position == 1 ? "方案1" : "方案2"), Toast.LENGTH_SHORT).show();
                } else {
                    prefs.edit()
                            .putString(PREF_TURN_KEYWORD_PRESET, preset)
                            .apply();
                }
                applyTurnKeywordPreset(preset);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        btnSaveTurnKeywords.setOnClickListener(v -> {
            int position = spinnerTurnKeywordPreset.getSelectedItemPosition();
            String preset;
            String left;
            String right;
            
            if (position == 1) {
                preset = TURN_KEYWORD_PRESET_SCHEME_1;
                left = TURN_KEYWORD_SCHEME_1_LEFT;
                right = TURN_KEYWORD_SCHEME_1_RIGHT;
            } else if (position == 2) {
                preset = TURN_KEYWORD_PRESET_SCHEME_2;
                left = TURN_KEYWORD_SCHEME_2_LEFT;
                right = TURN_KEYWORD_SCHEME_2_RIGHT;
            } else {
                preset = TURN_KEYWORD_PRESET_CUSTOM;
                left = etTurnKeywordLeft.getText() != null ? etTurnKeywordLeft.getText().toString().trim() : "";
                right = etTurnKeywordRight.getText() != null ? etTurnKeywordRight.getText().toString().trim() : "";
            }
            
            prefs.edit()
                    .putString(PREF_TURN_KEYWORD_PRESET, preset)
                    .putString(PREF_TURN_KEYWORD_LEFT, left)
                    .putString(PREF_TURN_KEYWORD_RIGHT, right)
                    .apply();
            notifySettingsChanged();
            Toast.makeText(this, "转向灯关键词已保存", Toast.LENGTH_SHORT).show();
        });

        etLogDebugKeyword = findViewById(R.id.et_log_debug_keyword);
        btnLogDebugMatch = findViewById(R.id.btn_log_debug_match);
        tvLogDebugOutput = findViewById(R.id.tv_log_debug_output);
        btnLogDebugMatch.setOnClickListener(v -> startLogDebugMatch());

        switchFileLog = findViewById(R.id.switch_file_log);
        boolean fileLogEnabled = prefs.getBoolean(PREF_DEBUG_FILE_LOGGING, false);
        switchFileLog.setChecked(fileLogEnabled);
        if (fileLogEnabled) {
            AppLog.startFileLogging(getApplicationContext());
        }
        switchFileLog.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_DEBUG_FILE_LOGGING, isChecked).apply();
            if (isChecked) {
                String path = AppLog.startFileLogging(getApplicationContext());
                if (path == null) {
                    prefs.edit().putBoolean(PREF_DEBUG_FILE_LOGGING, false).apply();
                    switchFileLog.setChecked(false);
                    Toast.makeText(this, "无法创建日志文件（请检查存储权限/Downloads可写）", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "开始写入日志：" + path, Toast.LENGTH_SHORT).show();
                }
            } else {
                String path = AppLog.stopFileLogging(getApplicationContext());
                if (path == null || path.isEmpty()) {
                    Toast.makeText(this, "日志文件已保存", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "日志文件已保存：" + path, Toast.LENGTH_LONG).show();
                }
            }
        });
        
        // ========== 设置按钮监听 ==========
        btnStartService.setOnClickListener(v -> startTurningBlindSpotService());
        btnStopService.setOnClickListener(v -> stopTurningBlindSpotService());
        btnPhotoToggle.setOnClickListener(v -> togglePhotoContinuous());
        btnPhotoOnce.setOnClickListener(v -> triggerPhotoOnce());
        btnPhotoCenter.setOnClickListener(v -> startActivity(new Intent(this, PhotoCenterActivity.class)));
        
        // 校准按钮
        Button btnCalibrate = findViewById(R.id.btn_calibrate);
        btnCalibrate.setOnClickListener(v -> {
            if (!TurningBlindSpotService.isRunning()) {
                Toast.makeText(this, "请先启动补盲服务", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, BlindspotCalibrationActivity.class);
            startActivity(intent);
        });
        
        // 模拟按钮
        findViewById(R.id.btn_simulate_left).setOnClickListener(v -> 
                simulateTurnSignal(TurningSignalMonitor.DIRECTION_LEFT, true));
        findViewById(R.id.btn_simulate_right).setOnClickListener(v -> 
                simulateTurnSignal(TurningSignalMonitor.DIRECTION_RIGHT, true));
        findViewById(R.id.btn_stop_simulate).setOnClickListener(v -> 
                simulateTurnSignal(TurningSignalMonitor.DIRECTION_NONE, false));
        
        // ========== 设置开关 ==========
        
        switchAutoStart.setChecked(prefs.getBoolean(PREF_AUTO_START_ON_BOOT, true));
        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_AUTO_START_ON_BOOT, isChecked).apply();
            Toast.makeText(this, isChecked
                    ? "开机自启动已开启。若无法自启，请在系统设置中关闭本应用的电池优化"
                    : "开机自启动已关闭", Toast.LENGTH_SHORT).show();
        });
        
        switchShowFloatingWindow.setChecked(prefs.getBoolean(PREF_SHOW_FLOATING_WINDOW, true));
        switchShowFloatingWindow.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_SHOW_FLOATING_WINDOW, isChecked).apply();
            notifySettingsChanged();
            Toast.makeText(this, isChecked ? "悬浮窗已开启" : "悬浮窗已关闭（仅HUD/仪表盘显示）", 
                    Toast.LENGTH_SHORT).show();
        });

        if (spinnerWindowRotationLeft != null && spinnerWindowRotationRight != null) {
            ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
            rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            rotationAdapter.add("0°");
            rotationAdapter.add("90°");
            rotationAdapter.add("180°");
            rotationAdapter.add("270°");
            spinnerWindowRotationLeft.setAdapter(rotationAdapter);
            spinnerWindowRotationRight.setAdapter(rotationAdapter);

            isInitializingWindowRotation = true;
            if (!prefs.contains(PREF_FLOATING_WINDOW_ROTATION_LEFT)
                    && !prefs.contains(PREF_FLOATING_WINDOW_ROTATION_RIGHT)
                    && prefs.contains(PREF_FLOATING_WINDOW_ROTATION)) {
                int legacy = prefs.getInt(PREF_FLOATING_WINDOW_ROTATION, 0);
                prefs.edit()
                        .putInt(PREF_FLOATING_WINDOW_ROTATION_LEFT, legacy)
                        .putInt(PREF_FLOATING_WINDOW_ROTATION_RIGHT, legacy)
                        .apply();
            }

            int leftDeg = prefs.getInt(PREF_FLOATING_WINDOW_ROTATION_LEFT, prefs.getInt(PREF_FLOATING_WINDOW_ROTATION, 0));
            int rightDeg = prefs.getInt(PREF_FLOATING_WINDOW_ROTATION_RIGHT, prefs.getInt(PREF_FLOATING_WINDOW_ROTATION, 0));
            int leftSel = leftDeg == 90 ? 1 : (leftDeg == 180 ? 2 : (leftDeg == 270 ? 3 : 0));
            int rightSel = rightDeg == 90 ? 1 : (rightDeg == 180 ? 2 : (rightDeg == 270 ? 3 : 0));
            spinnerWindowRotationLeft.setSelection(leftSel);
            spinnerWindowRotationRight.setSelection(rightSel);
            isInitializingWindowRotation = false;

            spinnerWindowRotationLeft.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (isInitializingWindowRotation) return;
                    int deg = position == 1 ? 90 : (position == 2 ? 180 : (position == 3 ? 270 : 0));
                    prefs.edit().putInt(PREF_FLOATING_WINDOW_ROTATION_LEFT, deg).apply();
                    notifySettingsChanged();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });

            spinnerWindowRotationRight.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (isInitializingWindowRotation) return;
                    int deg = position == 1 ? 90 : (position == 2 ? 180 : (position == 3 ? 270 : 0));
                    prefs.edit().putInt(PREF_FLOATING_WINDOW_ROTATION_RIGHT, deg).apply();
                    notifySettingsChanged();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
        
        switchIndicator.setChecked(prefs.getBoolean(PREF_SHOW_INDICATOR, true));
        switchIndicator.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PREF_SHOW_INDICATOR, isChecked).apply();
            Toast.makeText(this, isChecked ? "提醒条已开启" : "提醒条已关闭", Toast.LENGTH_SHORT).show();
        });
        
        photoStateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (VideoRecordController.ACTION_RECORD_STATE_CHANGED.equals(intent.getAction())) {
                    String p = intent.getStringExtra(VideoRecordController.EXTRA_RECORD_LAST_PATH);
                    if (p != null && !p.isEmpty()) lastRecordPath = p;
                    updatePhotoModuleUI();
                }
            }
        };
        
        // ========== 画质调节 ==========
        initImageQualitySettings();
    }

    private void initVehicleConfigViews() {
        ArrayAdapter<String> vehicleAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        vehicleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        vehicleAdapter.add("E02车型");
        vehicleAdapter.add("E02车型-25款星舰7/银河A7");
        vehicleAdapter.add("自定义车型");
        spinnerVehicleType.setAdapter(vehicleAdapter);

        segmentAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        segmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        segmentAdapter.add("A");
        segmentAdapter.add("B");
        segmentAdapter.add("C");
        segmentAdapter.add("D");
        spinnerMapFront.setAdapter(segmentAdapter);
        spinnerMapBack.setAdapter(segmentAdapter);
        spinnerMapLeft.setAdapter(segmentAdapter);
        spinnerMapRight.setAdapter(segmentAdapter);

        isInitializingVehicleUi = true;
        CameraLayoutConfig.VehicleType type = CameraLayoutConfig.getVehicleType(prefs);
        spinnerVehicleType.setSelection(type == CameraLayoutConfig.VehicleType.E02 ? 0
                : (type == CameraLayoutConfig.VehicleType.E02_STARSHIP7_25 ? 1 : 2));
        applyVehicleTypeVisibility(type);

        setSegmentSelection(spinnerMapFront, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.FRONT));
        setSegmentSelection(spinnerMapBack, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.BACK));
        setSegmentSelection(spinnerMapLeft, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.LEFT));
        setSegmentSelection(spinnerMapRight, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.RIGHT));
        isInitializingVehicleUi = false;

        spinnerVehicleType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializingVehicleUi) return;
                CameraLayoutConfig.VehicleType newType = position == 0 ? CameraLayoutConfig.VehicleType.E02
                        : (position == 1 ? CameraLayoutConfig.VehicleType.E02_STARSHIP7_25 : CameraLayoutConfig.VehicleType.CUSTOM);
                CameraLayoutConfig.setVehicleType(prefs, newType);
                applyVehicleTypeVisibility(newType);
                isInitializingVehicleUi = true;
                setSegmentSelection(spinnerMapFront,
                        CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.FRONT));
                setSegmentSelection(spinnerMapBack,
                        CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.BACK));
                setSegmentSelection(spinnerMapLeft,
                        CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.LEFT));
                setSegmentSelection(spinnerMapRight,
                        CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.RIGHT));
                isInitializingVehicleUi = false;
                notifySettingsChanged();
                Toast.makeText(MainActivity.this, "已切换车型配置（重启服务生效）", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        AdapterView.OnItemSelectedListener segmentListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializingVehicleUi) return;
                String raw = segmentAdapter.getItem(position);
                if (raw == null) return;
                CameraLayoutConfig.Segment seg = CameraLayoutConfig.Segment.valueOf(raw);
                if (parent == spinnerMapFront) {
                    CameraLayoutConfig.setE02Mapping(prefs, CameraLayoutConfig.Direction.FRONT, seg);
                } else if (parent == spinnerMapBack) {
                    CameraLayoutConfig.setE02Mapping(prefs, CameraLayoutConfig.Direction.BACK, seg);
                } else if (parent == spinnerMapLeft) {
                    CameraLayoutConfig.setE02Mapping(prefs, CameraLayoutConfig.Direction.LEFT, seg);
                } else if (parent == spinnerMapRight) {
                    CameraLayoutConfig.setE02Mapping(prefs, CameraLayoutConfig.Direction.RIGHT, seg);
                }
                notifySettingsChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spinnerMapFront.setOnItemSelectedListener(segmentListener);
        spinnerMapBack.setOnItemSelectedListener(segmentListener);
        spinnerMapLeft.setOnItemSelectedListener(segmentListener);
        spinnerMapRight.setOnItemSelectedListener(segmentListener);

        updateCameraResolutionLabel();
    }

    private void setSegmentSelection(Spinner spinner, CameraLayoutConfig.Segment segment) {
        int idx = segment.index();
        if (idx < 0) idx = 0;
        if (idx > 3) idx = 3;
        spinner.setSelection(idx);
    }

    private void applyVehicleTypeVisibility(CameraLayoutConfig.VehicleType type) {
        boolean isE02Family = type == CameraLayoutConfig.VehicleType.E02
                || type == CameraLayoutConfig.VehicleType.E02_STARSHIP7_25;
        layoutE02Mapping.setVisibility(isE02Family ? View.VISIBLE : View.GONE);
        layoutCustomRegion.setVisibility(type == CameraLayoutConfig.VehicleType.CUSTOM ? View.VISIBLE : View.GONE);
        updateCameraResolutionLabel();
    }

    private void updateCameraResolutionLabel() {
        if (tvCameraResolution == null) return;
        int w = CameraLayoutConfig.getOutputWidth(prefs);
        int h = CameraLayoutConfig.getOutputHeight(prefs);
        android.graphics.Bitmap frame = TurningBlindSpotService.getSharedFullFrame();
        if (frame != null && !frame.isRecycled()) {
            w = frame.getWidth();
            h = frame.getHeight();
        }
        tvCameraResolution.setText("输出分辨率：" + w + "×" + h);
    }

    private void applyTurnKeywordPreset(String preset) {
        boolean isScheme1 = TURN_KEYWORD_PRESET_SCHEME_1.equals(preset);
        boolean isScheme2 = TURN_KEYWORD_PRESET_SCHEME_2.equals(preset);
        etTurnKeywordLeft.setEnabled(!isScheme1 && !isScheme2);
        etTurnKeywordRight.setEnabled(!isScheme1 && !isScheme2);
        if (isScheme1) {
            etTurnKeywordLeft.setText(TURN_KEYWORD_SCHEME_1_LEFT);
            etTurnKeywordRight.setText(TURN_KEYWORD_SCHEME_1_RIGHT);
        } else if (isScheme2) {
            etTurnKeywordLeft.setText(TURN_KEYWORD_SCHEME_2_LEFT);
            etTurnKeywordRight.setText(TURN_KEYWORD_SCHEME_2_RIGHT);
        } else {
            etTurnKeywordLeft.setText(prefs.getString(PREF_TURN_KEYWORD_LEFT, ""));
            etTurnKeywordRight.setText(prefs.getString(PREF_TURN_KEYWORD_RIGHT, ""));
        }
    }

    private void syncVehicleConfigFromPrefs() {
        if (spinnerVehicleType == null) return;
        isInitializingVehicleUi = true;
        CameraLayoutConfig.VehicleType type = CameraLayoutConfig.getVehicleType(prefs);
        spinnerVehicleType.setSelection(type == CameraLayoutConfig.VehicleType.E02 ? 0
                : (type == CameraLayoutConfig.VehicleType.E02_STARSHIP7_25 ? 1 : 2));
        applyVehicleTypeVisibility(type);
        setSegmentSelection(spinnerMapFront, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.FRONT));
        setSegmentSelection(spinnerMapBack, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.BACK));
        setSegmentSelection(spinnerMapLeft, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.LEFT));
        setSegmentSelection(spinnerMapRight, CameraLayoutConfig.getE02Mapping(prefs, CameraLayoutConfig.Direction.RIGHT));
        isInitializingVehicleUi = false;
    }

    private void loadCameraIdList(boolean showToast) {
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (cameraManager == null) {
            if (showToast) Toast.makeText(this, "无法获取 CameraManager", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] cameraIds;
        try {
            cameraIds = cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            if (showToast) Toast.makeText(this, "读取 cameraId 失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        if (cameraIds == null || cameraIds.length == 0) {
            if (showToast) Toast.makeText(this, "未发现可用摄像头", Toast.LENGTH_SHORT).show();
            return;
        }

        String originalSavedId = prefs.getString(PREF_CAMERA_ID, null);
        String savedId = originalSavedId;
        CameraLayoutConfig.VehicleType vehicleType = CameraLayoutConfig.getVehicleType(prefs);
        isInitializingCameraSpinner = true;
        if (cameraIdAdapter == null) {
            cameraIdAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
            cameraIdAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinnerCameraId.setAdapter(cameraIdAdapter);
            spinnerCameraId.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (isInitializingCameraSpinner) return;
                    String selected = cameraIdAdapter.getItem(position);
                    if (selected == null) return;
                    prefs.edit().putString(PREF_CAMERA_ID, selected).apply();
                    Toast.makeText(MainActivity.this, "已选择摄像头ID: " + selected + "（重启服务生效）", Toast.LENGTH_SHORT)
                            .show();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        } else {
            cameraIdAdapter.clear();
        }

        int selectionIndex = 0;
        boolean savedIdMissing = savedId == null;
        if (savedIdMissing && (vehicleType == CameraLayoutConfig.VehicleType.E02
                || vehicleType == CameraLayoutConfig.VehicleType.E02_STARSHIP7_25)) {
            savedId = "1";
        }
        for (int i = 0; i < cameraIds.length; i++) {
            cameraIdAdapter.add(cameraIds[i]);
            if (savedId != null && savedId.equals(cameraIds[i])) {
                selectionIndex = i;
            }
        }
        cameraIdAdapter.notifyDataSetChanged();
        spinnerCameraId.setSelection(selectionIndex);
        isInitializingCameraSpinner = false;

        if (showToast) {
            Toast.makeText(this, "已刷新摄像头列表（共 " + cameraIds.length + " 个）", Toast.LENGTH_SHORT).show();
        }

        String selected = cameraIds[selectionIndex];
        if (savedIdMissing || originalSavedId == null || !selected.equals(originalSavedId)) {
            prefs.edit().putString(PREF_CAMERA_ID, selected).apply();
        }
    }
    
    private void initImageQualitySettings() {
        // 亮度
        int savedBrightness = prefs.getInt(PREF_BRIGHTNESS, 0) + 100;
        seekbarBrightness.setProgress(savedBrightness);
        updateBrightnessLabel(savedBrightness);
        seekbarBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateBrightnessLabel(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(PREF_BRIGHTNESS, seekBar.getProgress() - 100).apply();
                notifySettingsChanged();
            }
        });
        
        // 对比度
        int savedContrast = prefs.getInt(PREF_CONTRAST, 0) + 100;
        seekbarContrast.setProgress(savedContrast);
        updateContrastLabel(savedContrast);
        seekbarContrast.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateContrastLabel(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(PREF_CONTRAST, seekBar.getProgress() - 100).apply();
                notifySettingsChanged();
            }
        });
        
        // 白平衡
        int savedWB = prefs.getInt(PREF_WHITE_BALANCE, 50);
        seekbarWhiteBalance.setProgress(savedWB);
        updateWhiteBalanceLabel(savedWB);
        seekbarWhiteBalance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateWhiteBalanceLabel(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(PREF_WHITE_BALANCE, seekBar.getProgress()).apply();
                notifySettingsChanged();
            }
        });
        
        // 延迟
        int savedDelay = prefs.getInt(PREF_DISPLAY_DELAY, 0);
        seekbarDelay.setProgress(savedDelay);
        updateDelayLabel(savedDelay);
        seekbarDelay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateDelayLabel(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(PREF_DISPLAY_DELAY, seekBar.getProgress()).apply();
                notifySettingsChanged();
            }
        });

        int savedTurnCloseTimeoutSec = prefs.getInt(PREF_TURN_AUTO_CLOSE_TIMEOUT_SEC, 3);
        if (savedTurnCloseTimeoutSec < 3) savedTurnCloseTimeoutSec = 3;
        if (savedTurnCloseTimeoutSec > 30) savedTurnCloseTimeoutSec = 30;
        seekbarTurnCloseTimeout.setMin(3);
        seekbarTurnCloseTimeout.setMax(30);
        seekbarTurnCloseTimeout.setProgress(savedTurnCloseTimeoutSec);
        updateTurnCloseTimeoutLabel(savedTurnCloseTimeoutSec);
        seekbarTurnCloseTimeout.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int sec = Math.max(3, Math.min(30, progress));
                updateTurnCloseTimeoutLabel(sec);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                int sec = Math.max(3, Math.min(30, seekBar.getProgress()));
                prefs.edit().putInt(PREF_TURN_AUTO_CLOSE_TIMEOUT_SEC, sec).apply();
                notifySettingsChanged();
            }
        });
    }
    
    // ==================== UI 更新 ====================
    
    private void updateUI() {
        boolean cameraGranted = hasCameraPermission();
        boolean overlayGranted = hasOverlayPermission();
        boolean logsGranted = hasLogsPermission();
        
        updatePermissionStatus(tvCameraPermissionIcon, tvCameraPermission, "相机权限", cameraGranted);
        updatePermissionStatus(tvOverlayPermissionIcon, tvOverlayPermission, "悬浮窗权限", overlayGranted);
        updatePermissionStatus(tvLogsPermissionIcon, tvLogsPermission, "日志权限", logsGranted);
        
        boolean serviceRunning = TurningBlindSpotService.isRunning();
        tvStatus.setText(serviceRunning ? "运行中" : "已停止");
        tvStatus.setTextColor(getColor(serviceRunning ? R.color.status_running : R.color.status_stopped));
        tvStatus.setBackgroundResource(serviceRunning ? R.drawable.status_badge_running : R.drawable.status_badge_stopped);

        btnStartService.setEnabled(hasAllPermissions() && !serviceRunning);
        btnStartService.setAlpha((!hasAllPermissions() || serviceRunning) ? 0.5f : 1.0f);
        btnStopService.setEnabled(serviceRunning);
        btnStopService.setAlpha(serviceRunning ? 1.0f : 0.5f);
        updateCameraResolutionLabel();
        updatePhotoModuleUI();
    }

    private void updatePhotoModuleUI() {
        if (tvPhotoStatus == null || btnPhotoToggle == null) return;
        boolean enabled = prefs.getBoolean(VideoRecordController.PREF_RECORD_ENABLED,
                prefs.getBoolean(PhotoCaptureController.PREF_PHOTO_CONTINUOUS_ENABLED, false));
        int fps = prefs.getInt(VideoRecordController.PREF_RECORD_FPS, 0);
        if (fps <= 0) {
            long iv = Math.max(100, prefs.getLong(PhotoCaptureController.PREF_PHOTO_INTERVAL_MS, 1000));
            fps = Math.max(1, Math.min(25, (int) Math.round(1000.0 / (double) iv)));
        }
        int seg = prefs.getInt(VideoRecordController.PREF_RECORD_SEGMENT_SECONDS, 10);
        String mode = prefs.getString(VideoStorage.PREF_RECORD_STORE_MODE,
                prefs.getString(PhotoStorage.PREF_PHOTO_STORE_MODE, VideoStorage.STORE_MODE_INTERNAL));
        String loc;
        if (VideoStorage.STORE_MODE_USB_PATH.equals(mode)) {
            String base = prefs.getString(VideoStorage.PREF_RECORD_STORE_PATH,
                    prefs.getString(PhotoStorage.PREF_PHOTO_STORE_PATH, ""));
            loc = (base == null || base.isEmpty()) ? "U盘" : ("U盘:" + base);
        } else {
            loc = "DCIM/E02REC";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("录制：").append(enabled ? "开启" : "关闭");
        sb.append("  |  FPS：").append(fps);
        sb.append("  |  分片：").append(seg).append("s");
        sb.append("  |  位置：").append(loc);
        if (lastRecordPath != null && !lastRecordPath.isEmpty()) {
            sb.append("\n最近一段：").append(lastRecordPath);
        }
        tvPhotoStatus.setText(sb.toString());
        btnPhotoToggle.setText(enabled ? "停止录制" : "启动录制");
    }

    private void togglePhotoContinuous() {
        boolean enabled = prefs.getBoolean(VideoRecordController.PREF_RECORD_ENABLED,
                prefs.getBoolean(PhotoCaptureController.PREF_PHOTO_CONTINUOUS_ENABLED, false));
        Intent i = new Intent(this, TurningBlindSpotService.class);
        i.setAction(enabled ? TurningBlindSpotService.ACTION_RECORD_STOP
                : TurningBlindSpotService.ACTION_RECORD_START);
        startForegroundService(i);
        prefs.edit().putBoolean(VideoRecordController.PREF_RECORD_ENABLED, !enabled).apply();
        updatePhotoModuleUI();
    }

    private void triggerPhotoOnce() {
        Intent i = new Intent(this, TurningBlindSpotService.class);
        i.setAction(TurningBlindSpotService.ACTION_RECORD_TAKE_PHOTO);
        startForegroundService(i);
    }
    
    private void updatePermissionStatus(TextView iconView, TextView textView, String name, boolean granted) {
        if (granted) {
            iconView.setText("✓");
            iconView.setTextColor(getColor(R.color.status_running));
            iconView.setBackgroundResource(R.drawable.icon_circle_green);
            textView.setText(name);
            textView.setTextColor(getColor(R.color.text_primary));
        } else {
            iconView.setText("✗");
            iconView.setTextColor(getColor(R.color.status_stopped));
            iconView.setBackgroundResource(R.drawable.icon_circle_red);
            textView.setText(name);
            textView.setTextColor(getColor(R.color.text_secondary));
        }
    }
    
    private void updateBrightnessLabel(int progress) {
        int value = progress - 100;
        tvBrightnessValue.setText(value > 0 ? "+" + value : String.valueOf(value));
    }
    
    private void updateContrastLabel(int progress) {
        int value = progress - 100;
        tvContrastValue.setText(value > 0 ? "+" + value : String.valueOf(value));
    }
    
    private void updateWhiteBalanceLabel(int progress) {
        if (progress == 50) {
            tvWhiteBalanceValue.setText("自动");
        } else if (progress < 50) {
            tvWhiteBalanceValue.setText("冷" + (50 - progress));
        } else {
            tvWhiteBalanceValue.setText("暖" + (progress - 50));
        }
    }
    
    private void updateDelayLabel(int progress) {
        tvDelayValue.setText(progress + "ms");
    }

    private void updateTurnCloseTimeoutLabel(int sec) {
        tvTurnCloseTimeoutValue.setText(sec + "s");
    }
    
    // 鱼眼矫正已移除（改用校准界面的裁切框调整）

    private void startLogDebugMatch() {
        if (isLogDebugRunning) {
            Toast.makeText(this, "调试匹配进行中", Toast.LENGTH_SHORT).show();
            return;
        }
        String raw = etLogDebugKeyword.getText() != null ? etLogDebugKeyword.getText().toString().trim() : "";
        if (raw.isEmpty()) {
            Toast.makeText(this, "请输入调试关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<String> keys = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            if (part == null) continue;
            String k = part.trim().toLowerCase();
            if (!k.isEmpty()) keys.add(k);
        }
        if (keys.isEmpty()) {
            Toast.makeText(this, "请输入调试关键词", Toast.LENGTH_SHORT).show();
            return;
        }

        isLogDebugRunning = true;
        btnLogDebugMatch.setEnabled(false);
        btnLogDebugMatch.setAlpha(0.5f);
        tvLogDebugOutput.setText("匹配中...");

        new Thread(() -> {
            int scanned = 0;
            int matched = 0;
            boolean truncated = false;
            String error = null;
            StringBuilder out = new StringBuilder();
            Process p = null;
            try {
                p = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", "2000")
                        .redirectErrorStream(true)
                        .start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    scanned++;
                    String lower = line.toLowerCase();
                    boolean hit = false;
                    for (int i = 0; i < keys.size(); i++) {
                        if (lower.contains(keys.get(i))) {
                            hit = true;
                            break;
                        }
                    }
                    if (!hit) continue;
                    matched++;
                    if (matched <= 120) {
                        if (out.length() < 15000) {
                            out.append(line).append('\n');
                        } else {
                            truncated = true;
                            break;
                        }
                    } else {
                        truncated = true;
                        break;
                    }
                }
                try {
                    p.waitFor();
                } catch (InterruptedException ignored) {
                }
            } catch (Exception e) {
                error = e.getMessage();
            } finally {
                if (p != null) p.destroy();
            }

            String header = "扫描 " + scanned + " 行，命中 " + matched + " 行";
            if (truncated) header += "（已截断）";
            if (error != null) {
                header += "\n\n失败: " + error;
            } else if (out.length() == 0) {
                header += "\n\n未匹配到日志";
            } else {
                header += "\n\n" + out;
            }

            String finalText = header;
            mainHandler.post(() -> {
                tvLogDebugOutput.setText(finalText);
                btnLogDebugMatch.setEnabled(true);
                btnLogDebugMatch.setAlpha(1f);
                isLogDebugRunning = false;
            });
        }).start();
    }
    
    // ==================== 权限管理 ====================
    
    private boolean hasAllPermissions() {
        return hasCameraPermission() && hasOverlayPermission();
    }
    
    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasOverlayPermission() {
        return Settings.canDrawOverlays(this);
    }
    
    private boolean hasLogsPermission() {
        return checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }
    
    // ==================== 权限轮询 ====================
    
    private void startPermissionCheck() {
        if (permissionCheckRunnable == null) {
            permissionCheckRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isResumed) {
                        updateUI();
                        mainHandler.postDelayed(this, PERMISSION_CHECK_INTERVAL);
                    }
                }
            };
        }
        mainHandler.removeCallbacks(permissionCheckRunnable);
        mainHandler.postDelayed(permissionCheckRunnable, PERMISSION_CHECK_INTERVAL);
    }
    
    private void stopPermissionCheck() {
        if (permissionCheckRunnable != null) {
            mainHandler.removeCallbacks(permissionCheckRunnable);
        }
    }
    
    // ==================== 服务控制 ====================
    
    private void startTurningBlindSpotService() {
        if (!hasAllPermissions()) {
            Toast.makeText(this, "请先在系统设置中授予权限", Toast.LENGTH_SHORT).show();
            return;
        }
        btnStartService.setEnabled(false);
        btnStartService.setAlpha(0.5f);
        tvStatus.setText("启动中...");
        tvStatus.setTextColor(getColor(R.color.text_secondary));
        
        new Thread(() -> {
            Intent intent = new Intent(this, TurningBlindSpotService.class);
            startForegroundService(intent);
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            mainHandler.post(() -> {
                updateUI();
                Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
    
    private void stopTurningBlindSpotService() {
        btnStopService.setEnabled(false);
        btnStopService.setAlpha(0.5f);
        tvStatus.setText("停止中...");
        tvStatus.setTextColor(getColor(R.color.text_secondary));
        
        new Thread(() -> {
            Intent intent = new Intent(this, TurningBlindSpotService.class);
            stopService(intent);
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            mainHandler.post(() -> {
                updateUI();
                Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }
    
    // ==================== 工具方法 ====================
    
    private void notifySettingsChanged() {
        Intent intent = new Intent("com.elliot.blindspot.SETTINGS_CHANGED");
        sendBroadcast(intent);
    }
    
    private void simulateTurnSignal(String direction, boolean isOn) {
        Toast.makeText(this, "发送模拟信号: " + direction + " (" + isOn + ")", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(TurningSignalMonitor.ACTION_TURNING_SIGNAL_CHANGED);
        intent.putExtra(TurningSignalMonitor.EXTRA_TURNING_DIRECTION, direction);
        intent.putExtra(TurningSignalMonitor.EXTRA_IS_TURNING_ON, isOn);
        sendBroadcast(intent);
    }
    
}
