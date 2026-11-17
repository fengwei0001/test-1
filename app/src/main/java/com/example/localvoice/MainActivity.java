package com.example.localvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Chronometer;
import android.widget.GridLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuation;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String TAG = "LocalVoice";
    private static final int SAMPLE_RATE = 16_000;
    private static final int AUDIO_PERMISSION_REQUEST = 1001;

    private enum Engine {
        ZIPFORMER,
        ONLINE_PARAFORMER,
        OFFLINE_PARAFORMER,
        SENSEVOICE,
        SMART,
        VAD_MODE,
        ANDROID_SYSTEM
    }

    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();

    private TextView engineNameText;
    private TextView subtitleText;
    private TextView statusText;
    private TextView transcriptText;
    private ScrollView transcriptScroll;
    private Chronometer recordingTimer;
    private HoldToTalkButton recordButton;
    private RadioButton zipformerOption;
    private RadioButton onlineParaformerOption;
    private RadioButton offlineParaformerOption;
    private RadioButton senseVoiceOption;
    private RadioButton smartOption;
    private RadioButton vadOption;
    private RadioButton systemOption;

    private volatile boolean isRecording;
    private volatile boolean activityDestroyed;
    private volatile AudioRecord audioRecord;
    private Engine selectedEngine = Engine.ZIPFORMER;
    private Engine recordingEngine;
    private OnlineRecognizer zipformerRecognizer;
    private OnlineRecognizer onlineParaformerRecognizer;
    private OfflineRecognizer offlineParaformerRecognizer;
    private OfflineRecognizer senseVoiceRecognizer;
    private volatile OfflinePunctuation punctuation;
    private SystemSpeechController systemSpeechController;
    private Thread recordingThread;
    private int modelGeneration;
    private boolean modelLoading;
    private boolean modelReady;
    private boolean processing;
    private boolean suppressNextClick;
    private String displayedText = "";

    private final SystemSpeechController.Listener systemListener =
            new SystemSpeechController.Listener() {
                @Override
                public void onStatus(int messageRes, boolean ready) {
                    if (activityDestroyed || selectedEngine != Engine.ANDROID_SYSTEM) {
                        return;
                    }
                    modelLoading = false;
                    modelReady = ready;
                    statusText.setText(messageRes);
                    updateControls();
                }

                @Override
                public void onDownloadProgress(int percent) {
                    if (activityDestroyed || selectedEngine != Engine.ANDROID_SYSTEM) {
                        return;
                    }
                    modelLoading = false;
                    modelReady = false;
                    statusText.setText(getString(
                            R.string.system_download_progress, percent));
                    updateControls();
                }

                @Override
                public void onPartialResult(String text) {
                    displayTranscript(text);
                }

                @Override
                public void onFinalResult(String text) {
                    finishRecording(text, null);
                }

                @Override
                public void onSessionError(int error) {
                    if (activityDestroyed) {
                        return;
                    }
                    boolean noMatch = error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
                    finishRecording("", noMatch ? null
                            : new IllegalStateException("System recognizer error " + error));
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        statusText.setText(R.string.system_language_unavailable);
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engineNameText = findViewById(R.id.engineNameText);
        subtitleText = findViewById(R.id.subtitleText);
        statusText = findViewById(R.id.statusText);
        transcriptText = findViewById(R.id.transcriptText);
        transcriptScroll = findViewById(R.id.transcriptScroll);
        recordingTimer = findViewById(R.id.recordingTimer);
        recordButton = findViewById(R.id.recordButton);
        zipformerOption = findViewById(R.id.zipformerOption);
        onlineParaformerOption = findViewById(R.id.onlineParaformerOption);
        offlineParaformerOption = findViewById(R.id.offlineParaformerOption);
        senseVoiceOption = findViewById(R.id.senseVoiceOption);
        smartOption = findViewById(R.id.smartOption);
        vadOption = findViewById(R.id.vadOption);
        systemOption = findViewById(R.id.systemOption);

        zipformerOption.setOnClickListener(v -> selectEngine(Engine.ZIPFORMER));
        onlineParaformerOption.setOnClickListener(v -> selectEngine(Engine.ONLINE_PARAFORMER));
        offlineParaformerOption.setOnClickListener(v -> selectEngine(Engine.OFFLINE_PARAFORMER));
        senseVoiceOption.setOnClickListener(v -> selectEngine(Engine.SENSEVOICE));
        smartOption.setOnClickListener(v -> selectEngine(Engine.SMART));
        vadOption.setOnClickListener(v -> selectEngine(Engine.VAD_MODE));
        systemOption.setOnClickListener(v -> selectEngine(Engine.ANDROID_SYSTEM));

        systemSpeechController = new SystemSpeechController(this, systemListener);
        recordButton.setOnTouchListener(this::onRecordButtonTouch);
        // 键盘、Switch Access 等无障碍操作可以用单击开始/停止，不依赖长按。
        recordButton.setOnClickListener(v -> onRecordButtonClick());

        updateCheckedEngineOption();
        updateEngineText();
        initializePunctuation();
        initializeSelectedModel();
    }

    private void selectEngine(Engine engine) {
        if (activityDestroyed) {
            return;
        }
        if (engine == selectedEngine) {
            updateCheckedEngineOption();
            return;
        }
        if (modelLoading || isRecording || processing) {
            updateCheckedEngineOption();
            return;
        }

        selectedEngine = engine;
        updateCheckedEngineOption();
        updateEngineText();
        resetTranscript();
        initializeSelectedModel();
    }

    private void updateCheckedEngineOption() {
        zipformerOption.setChecked(selectedEngine == Engine.ZIPFORMER);
        onlineParaformerOption.setChecked(selectedEngine == Engine.ONLINE_PARAFORMER);
        offlineParaformerOption.setChecked(selectedEngine == Engine.OFFLINE_PARAFORMER);
        senseVoiceOption.setChecked(selectedEngine == Engine.SENSEVOICE);
        smartOption.setChecked(selectedEngine == Engine.SMART);
        vadOption.setChecked(selectedEngine == Engine.VAD_MODE);
        systemOption.setChecked(selectedEngine == Engine.ANDROID_SYSTEM);
    }

    private void updateEngineText() {
        engineNameText.setText(getEngineTitleRes(selectedEngine));
        subtitleText.setText(getEngineSubtitleRes(selectedEngine));
    }

    private static int getEngineTitleRes(Engine engine) {
        switch (engine) {
            case ONLINE_PARAFORMER:
                return R.string.title_online_paraformer;
            case OFFLINE_PARAFORMER:
                return R.string.title_offline_paraformer;
            case SENSEVOICE:
                return R.string.title_sensevoice;
            case SMART:
                return R.string.title_smart;
            case VAD_MODE:
                return R.string.title_vad;
            case ANDROID_SYSTEM:
                return R.string.title_system;
            case ZIPFORMER:
            default:
                return R.string.title_zipformer;
        }
    }

    private static int getEngineSubtitleRes(Engine engine) {
        switch (engine) {
            case ONLINE_PARAFORMER:
                return R.string.subtitle_online_paraformer;
            case OFFLINE_PARAFORMER:
                return R.string.subtitle_offline_paraformer;
            case SENSEVOICE:
                return R.string.subtitle_sensevoice;
            case SMART:
                return R.string.subtitle_smart;
            case VAD_MODE:
                return R.string.subtitle_vad;
            case ANDROID_SYSTEM:
                return R.string.subtitle_system;
            case ZIPFORMER:
            default:
                return R.string.subtitle_zipformer;
        }
    }

    private static int getEngineLabelRes(Engine engine) {
        switch (engine) {
            case ONLINE_PARAFORMER:
                return R.string.label_online_paraformer;
            case OFFLINE_PARAFORMER:
                return R.string.label_offline_paraformer;
            case SENSEVOICE:
                return R.string.label_sensevoice;
            case SMART:
                return R.string.label_smart;
            case VAD_MODE:
                return R.string.label_vad;
            case ANDROID_SYSTEM:
                return R.string.label_system;
            case ZIPFORMER:
            default:
                return R.string.label_zipformer;
        }
    }

    private void initializeSelectedModel() {
        final Engine engine = selectedEngine;
        if (engine == Engine.ANDROID_SYSTEM) {
            modelReady = false;
            modelLoading = true;
            statusText.setText(R.string.system_checking);
            updateControls();
            systemSpeechController.initialize();
            return;
        }
        final int generation = ++modelGeneration;
        modelReady = false;
        updateControls();

        if (isEngineLoaded(engine)) {
            modelLoading = false;
            modelReady = true;
            showReadyStatus(engine);
            updateControls();
            return;
        }

        modelLoading = true;
        statusText.setText(R.string.model_loading);
        updateControls();
        modelExecutor.execute(() -> {
            try {
                if (engine == Engine.SMART) {
                    loadSmartRecognizers(generation);
                    return;
                }
                if (isOnlineEngine(engine)) {
                    OnlineRecognizer created = new OnlineRecognizer(
                            getAssets(), createOnlineConfig(engine));
                    runOnUiThread(() -> {
                        if (activityDestroyed) {
                            created.release();
                            return;
                        }
                        setOnlineRecognizer(engine, created);
                        onModelLoaded(engine, generation);
                    });
                } else {
                    OfflineRecognizer created = new OfflineRecognizer(
                            getAssets(), createOfflineConfig(engine));
                    runOnUiThread(() -> {
                        if (activityDestroyed) {
                            created.release();
                            return;
                        }
                        setOfflineRecognizer(engine, created);
                        onModelLoaded(engine, generation);
                    });
                }
            } catch (Throwable error) {
                Log.e(TAG, "Failed to initialize " + engine, error);
                runOnUiThread(() -> onModelFailed(engine, generation));
            }
        });
    }

    private void loadSmartRecognizers(int generation) {
        OnlineRecognizer newZipformer = null;
        OfflineRecognizer newSenseVoice = null;
        try {
            if (zipformerRecognizer == null) {
                newZipformer = new OnlineRecognizer(
                        getAssets(), createOnlineConfig(Engine.ZIPFORMER));
            }
            if (senseVoiceRecognizer == null) {
                newSenseVoice = new OfflineRecognizer(
                        getAssets(), createOfflineConfig(Engine.SENSEVOICE));
            }
            publishSmartRecognizers(newZipformer, newSenseVoice, generation);
        } catch (Throwable error) {
            if (newZipformer != null) {
                newZipformer.release();
            }
            if (newSenseVoice != null) {
                newSenseVoice.release();
            }
            Log.e(TAG, "Failed to initialize smart mode", error);
            runOnUiThread(() -> onModelFailed(Engine.SMART, generation));
        }
    }

    private void publishSmartRecognizers(
            OnlineRecognizer newZipformer,
            OfflineRecognizer newSenseVoice,
            int generation) {
        runOnUiThread(() -> {
            if (activityDestroyed) {
                if (newZipformer != null) {
                    newZipformer.release();
                }
                if (newSenseVoice != null) {
                    newSenseVoice.release();
                }
                return;
            }
            if (newZipformer != null) {
                zipformerRecognizer = newZipformer;
            }
            if (newSenseVoice != null) {
                senseVoiceRecognizer = newSenseVoice;
            }
            onModelLoaded(Engine.SMART, generation);
        });
    }

    private void onModelLoaded(Engine engine, int generation) {
        if (activityDestroyed || generation != modelGeneration || engine != selectedEngine) {
            return;
        }
        modelLoading = false;
        modelReady = true;
        showReadyStatus(engine);
        updateControls();
    }

    private void onModelFailed(Engine engine, int generation) {
        if (activityDestroyed || generation != modelGeneration || engine != selectedEngine) {
            return;
        }
        modelLoading = false;
        modelReady = false;
        statusText.setText(R.string.model_failed);
        updateControls();
    }

    private void showReadyStatus(Engine engine) {
        statusText.setText(getString(
                R.string.model_ready_format, getString(getEngineLabelRes(engine))));
    }

    private boolean isEngineLoaded(Engine engine) {
        switch (engine) {
            case ANDROID_SYSTEM:
                return systemSpeechController.isReady();
            case SMART:
                return zipformerRecognizer != null && senseVoiceRecognizer != null;
            case ONLINE_PARAFORMER:
                return onlineParaformerRecognizer != null;
            case OFFLINE_PARAFORMER:
                return offlineParaformerRecognizer != null;
            case SENSEVOICE:
            case VAD_MODE:
                return senseVoiceRecognizer != null;
            case ZIPFORMER:
            default:
                return zipformerRecognizer != null;
        }
    }

    private void setOnlineRecognizer(Engine engine, OnlineRecognizer recognizer) {
        if (engine == Engine.ONLINE_PARAFORMER) {
            onlineParaformerRecognizer = recognizer;
        } else {
            zipformerRecognizer = recognizer;
        }
    }

    private void setOfflineRecognizer(Engine engine, OfflineRecognizer recognizer) {
        if (usesSenseVoice(engine)) {
            senseVoiceRecognizer = recognizer;
        } else {
            offlineParaformerRecognizer = recognizer;
        }
    }

    private OnlineRecognizer getOnlineRecognizer(Engine engine) {
        return engine == Engine.ONLINE_PARAFORMER
                ? onlineParaformerRecognizer : zipformerRecognizer;
    }

    private OfflineRecognizer getOfflineRecognizer(Engine engine) {
        return usesSenseVoice(engine) ? senseVoiceRecognizer : offlineParaformerRecognizer;
    }

    private static boolean usesSenseVoice(Engine engine) {
        return engine == Engine.SENSEVOICE || engine == Engine.SMART || engine == Engine.VAD_MODE;
    }

    private static boolean isOnlineEngine(Engine engine) {
        return engine == Engine.ZIPFORMER || engine == Engine.ONLINE_PARAFORMER
                || engine == Engine.SMART;
    }

    private void updateControls() {
        boolean engineLocked = modelLoading || isRecording || processing;
        zipformerOption.setEnabled(!engineLocked);
        onlineParaformerOption.setEnabled(!engineLocked);
        offlineParaformerOption.setEnabled(!engineLocked);
        senseVoiceOption.setEnabled(!engineLocked);
        smartOption.setEnabled(!engineLocked);
        vadOption.setEnabled(!engineLocked);
        systemOption.setEnabled(!engineLocked);
        recordButton.setEnabled(isRecording || (modelReady && !processing));
    }

    private FeatureConfig createFeatureConfig() {
        FeatureConfig featureConfig = new FeatureConfig();
        featureConfig.setSampleRate(SAMPLE_RATE);
        featureConfig.setFeatureDim(80);
        featureConfig.setDither(0.0f);
        return featureConfig;
    }

    private OnlineRecognizerConfig createOnlineConfig(Engine engine) {
        OnlineModelConfig modelConfig = new OnlineModelConfig();
        if (engine == Engine.ONLINE_PARAFORMER) {
            OnlineParaformerModelConfig paraformer = new OnlineParaformerModelConfig();
            paraformer.setEncoder("online-paraformer/encoder.int8.onnx");
            paraformer.setDecoder("online-paraformer/decoder.int8.onnx");
            modelConfig.setParaformer(paraformer);
            modelConfig.setTokens("online-paraformer/tokens.txt");
            modelConfig.setModelType("paraformer");
        } else {
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder("model/encoder-epoch-99-avg-1.int8.onnx");
            transducer.setDecoder("model/decoder-epoch-99-avg-1.onnx");
            transducer.setJoiner("model/joiner-epoch-99-avg-1.int8.onnx");
            modelConfig.setTransducer(transducer);
            modelConfig.setTokens("model/tokens.txt");
            modelConfig.setModelType("zipformer");
        }
        modelConfig.setProvider("cpu");
        modelConfig.setNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
        modelConfig.setDebug(false);

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(createFeatureConfig());
        config.setModelConfig(modelConfig);
        config.setEnableEndpoint(false);
        config.setDecodingMethod("greedy_search");
        return config;
    }

    private OfflineRecognizerConfig createOfflineConfig(Engine engine) {
        OfflineModelConfig modelConfig = new OfflineModelConfig();
        if (usesSenseVoice(engine)) {
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
            senseVoice.setModel("sense-voice/model.int8.onnx");
            senseVoice.setLanguage("auto");
            senseVoice.setUseInverseTextNormalization(true);
            modelConfig.setSenseVoice(senseVoice);
            modelConfig.setTokens("sense-voice/tokens.txt");
            modelConfig.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
        } else {
            OfflineParaformerModelConfig paraformer = new OfflineParaformerModelConfig();
            paraformer.setModel("offline-paraformer/model.int8.onnx");
            modelConfig.setParaformer(paraformer);
            modelConfig.setTokens("offline-paraformer/tokens.txt");
            modelConfig.setModelType("paraformer");
            modelConfig.setNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())));
        }
        modelConfig.setProvider("cpu");
        modelConfig.setDebug(false);

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(createFeatureConfig());
        config.setModelConfig(modelConfig);
        config.setDecodingMethod("greedy_search");
        return config;
    }

    private void initializePunctuation() {
        modelExecutor.execute(() -> {
            try {
                OfflinePunctuationModelConfig modelConfig = new OfflinePunctuationModelConfig();
                modelConfig.setCtTransformer("punctuation/model.int8.onnx");
                modelConfig.setProvider("cpu");
                modelConfig.setNumThreads(1);
                modelConfig.setDebug(false);

                OfflinePunctuationConfig config = new OfflinePunctuationConfig(modelConfig);
                OfflinePunctuation created = new OfflinePunctuation(getAssets(), config);
                runOnUiThread(() -> {
                    if (activityDestroyed) {
                        created.release();
                    } else {
                        punctuation = created;
                    }
                });
            } catch (Throwable error) {
                // 标点只是后处理增强，失败时仍保留原始 ASR 结果。
                Log.w(TAG, "Unable to initialize punctuation restoration", error);
            }
        });
    }

    private boolean onRecordButtonTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                suppressNextClick = true;
                if (modelReady && !processing && hasAudioPermission()) {
                    startRecording();
                } else if (modelReady && !processing) {
                    requestAudioPermission();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (isRecording) {
                    stopRecording();
                }
                view.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                suppressNextClick = false;
                if (isRecording) {
                    stopRecording();
                }
                return true;
            default:
                return false;
        }
    }

    private void onRecordButtonClick() {
        if (suppressNextClick) {
            suppressNextClick = false;
            return;
        }
        if (!modelReady || processing) {
            return;
        }
        if (isRecording) {
            stopRecording();
        } else if (hasAudioPermission()) {
            startRecording();
        } else {
            requestAudioPermission();
        }
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        statusText.setText(R.string.permission_needed);
        requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != AUDIO_PERMISSION_REQUEST) {
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            statusText.setText(R.string.permission_granted);
            return;
        }

        statusText.setText(R.string.permission_denied);
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showPermissionSettingsDialog();
        }
    }

    private void showPermissionSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_settings_title)
                .setMessage(R.string.permission_settings_message)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void startRecording() {
        final Engine engine = selectedEngine;
        if (isRecording || processing || !modelReady || !isEngineLoaded(engine)) {
            return;
        }
        // 在创建 AudioRecord 的方法内再次检查，兼顾权限被系统临时撤销的情况。
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission();
            return;
        }

        if (engine == Engine.ANDROID_SYSTEM) {
            startSystemSpeechRecognition();
            return;
        }

        int minimumBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBufferSize <= 0) {
            showMicrophoneError(null);
            return;
        }

        AudioRecord newAudioRecord;
        try {
            newAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimumBufferSize * 2, SAMPLE_RATE / 5));
        } catch (IllegalArgumentException | SecurityException error) {
            showMicrophoneError(error);
            return;
        }
        if (newAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            newAudioRecord.release();
            showMicrophoneError(null);
            return;
        }

        try {
            newAudioRecord.startRecording();
        } catch (IllegalStateException | SecurityException error) {
            newAudioRecord.release();
            showMicrophoneError(error);
            return;
        }

        recordingEngine = engine;
        audioRecord = newAudioRecord;
        isRecording = true;
        processing = false;
        if (isOnlineEngine(engine)) {
            if (engine == Engine.SMART) {
                displayedText = "";
                displayTranscript(getString(R.string.smart_waiting));
            } else {
                resetTranscript();
            }
            statusText.setText(R.string.recording_streaming);
        } else {
            displayedText = "";
            displayTranscript(getString(engine == Engine.VAD_MODE
                    ? R.string.vad_waiting : R.string.offline_waiting));
            statusText.setText(R.string.recording_offline);
        }
        recordButton.setText(R.string.listening);
        recordingTimer.setBase(SystemClock.elapsedRealtime());
        recordingTimer.start();
        updateControls();

        recordingThread = new Thread(
                () -> processAudio(newAudioRecord, engine), "local-voice-recognition");
        recordingThread.start();
    }

    private void startSystemSpeechRecognition() {
        recordingEngine = Engine.ANDROID_SYSTEM;
        isRecording = true;
        processing = false;
        displayedText = "";
        displayTranscript(getString(R.string.system_waiting));
        statusText.setText(R.string.recording_streaming);
        recordButton.setText(R.string.listening);
        recordingTimer.setBase(SystemClock.elapsedRealtime());
        recordingTimer.start();
        updateControls();
        try {
            systemSpeechController.start();
        } catch (Throwable error) {
            Log.e(TAG, "Unable to start system speech recognition", error);
            isRecording = false;
            processing = false;
            recordingEngine = null;
            recordingTimer.stop();
            statusText.setText(R.string.recognition_failed);
            recordButton.setText(R.string.hold_to_talk);
            updateControls();
        }
    }

    private void processAudio(AudioRecord record, Engine engine) {
        if (engine == Engine.VAD_MODE) {
            processVadAudio(record);
            return;
        }
        final boolean online = isOnlineEngine(engine);
        final OnlineRecognizer onlineRecognizer = online ? getOnlineRecognizer(engine) : null;
        OnlineStream onlineStream = null;
        ByteArrayOutputStream offlineAudio = !online || engine == Engine.SMART
                ? new ByteArrayOutputStream(SAMPLE_RATE * 2 * 10) : null;
        String latestText = "";
        Throwable recognitionError = null;
        boolean audioReleased = false;
        try {
            if (online) {
                onlineStream = onlineRecognizer.createStream("");
            }
            short[] audioBuffer = new short[SAMPLE_RATE / 10];
            String previousPartial = "";

            while (isRecording && !activityDestroyed) {
                int samplesRead = record.read(audioBuffer, 0, audioBuffer.length);
                if (samplesRead < 0) {
                    throw new IllegalStateException("AudioRecord.read() returned " + samplesRead);
                }
                if (samplesRead == 0) {
                    continue;
                }

                if (offlineAudio != null) {
                    writePcmBytes(offlineAudio, audioBuffer, samplesRead);
                }
                if (!online) {
                    continue;
                }

                float[] samples = toFloatSamples(audioBuffer, samplesRead);
                onlineStream.acceptWaveform(samples, SAMPLE_RATE);
                decodeAvailableFrames(onlineRecognizer, onlineStream);

                latestText = onlineRecognizer.getResult(onlineStream).getText().trim();
                if (!latestText.equals(previousPartial)) {
                    previousPartial = latestText;
                    String partial = latestText;
                    runOnUiThread(() -> displayTranscript(partial));
                }
            }

            // 推理前先关闭麦克风，整段识别期间不会一直占用录音设备。
            stopAndReleaseAudioRecord(record);
            audioReleased = true;
            audioRecord = null;

            if (!activityDestroyed && online) {
                int tailSamples = engine == Engine.ONLINE_PARAFORMER
                        ? (int) (SAMPLE_RATE * 0.8f) : SAMPLE_RATE / 2;
                onlineStream.acceptWaveform(new float[tailSamples], SAMPLE_RATE);
                onlineStream.inputFinished();
                decodeAvailableFrames(onlineRecognizer, onlineStream);
                String finalText = onlineRecognizer.getResult(onlineStream).getText().trim();
                if (!finalText.isEmpty()) {
                    latestText = finalText;
                }
                if (engine == Engine.SMART) {
                    latestText = transcribeOffline(engine, toShortArray(offlineAudio));
                } else {
                    latestText = addPunctuationIfNeeded(engine, latestText);
                }
            } else if (!activityDestroyed) {
                latestText = transcribeOffline(engine, toShortArray(offlineAudio));
            }
        } catch (Throwable error) {
            recognitionError = error;
            Log.e(TAG, "Audio capture or on-device recognition failed", error);
        } finally {
            if (!audioReleased) {
                stopAndReleaseAudioRecord(record);
                audioRecord = null;
            }
            if (onlineStream != null) {
                onlineStream.release();
            }

            String result = latestText;
            Throwable error = recognitionError;
            runOnUiThread(() -> finishRecording(result, error));
        }
    }

    private void processVadAudio(AudioRecord record) {
        Vad vad = null;
        ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
        List<Future<String>> tasks = new ArrayList<>();
        String completedText = "";
        Throwable recognitionError = null;
        boolean audioReleased = false;
        try {
            vad = createVad();
            short[] audioBuffer = new short[SAMPLE_RATE / 10];
            float[] vadWindow = new float[512];
            int bufferedSamples = 0;

            while (isRecording && !activityDestroyed) {
                int samplesRead = record.read(audioBuffer, 0, audioBuffer.length);
                if (samplesRead < 0) {
                    throw new IllegalStateException("AudioRecord.read() returned " + samplesRead);
                }
                if (samplesRead == 0) {
                    continue;
                }

                float[] samples = toFloatSamples(audioBuffer, samplesRead);
                for (float sample : samples) {
                    vadWindow[bufferedSamples++] = sample;
                    if (bufferedSamples == vadWindow.length) {
                        vad.acceptWaveform(vadWindow);
                        bufferedSamples = 0;
                        queueVadSegments(vad, inferenceExecutor, tasks);
                        completedText = collectVadResults(tasks, completedText, false);
                    }
                }
            }

            stopAndReleaseAudioRecord(record);
            audioReleased = true;
            audioRecord = null;

            if (!activityDestroyed) {
                if (bufferedSamples > 0) {
                    Arrays.fill(vadWindow, bufferedSamples, vadWindow.length, 0.0f);
                    vad.acceptWaveform(vadWindow);
                }
                vad.flush();
                queueVadSegments(vad, inferenceExecutor, tasks);
            }
            completedText = collectVadResults(tasks, completedText, true);
        } catch (Throwable error) {
            recognitionError = error;
            Log.e(TAG, "VAD capture or SenseVoice recognition failed", error);
        } finally {
            if (!audioReleased) {
                stopAndReleaseAudioRecord(record);
                audioRecord = null;
            }
            if (vad != null) {
                vad.release();
            }
            inferenceExecutor.shutdown();
            try {
                if (!inferenceExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Timed out waiting for VAD inference tasks");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                recognitionError = error;
            }

            String result = completedText;
            Throwable error = recognitionError;
            runOnUiThread(() -> finishRecording(result, error));
        }
    }

    private Vad createVad() {
        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel("vad/silero_vad.onnx");
        silero.setThreshold(0.5f);
        silero.setMinSilenceDuration(0.45f);
        silero.setMinSpeechDuration(0.2f);
        silero.setWindowSize(512);
        silero.setMaxSpeechDuration(12.0f);

        VadModelConfig config = new VadModelConfig();
        config.setSileroVadModelConfig(silero);
        config.setSampleRate(SAMPLE_RATE);
        config.setNumThreads(1);
        config.setProvider("cpu");
        config.setDebug(false);
        return new Vad(getAssets(), config);
    }

    private void queueVadSegments(
            Vad vad, ExecutorService inferenceExecutor, List<Future<String>> tasks) {
        while (!vad.empty()) {
            float[] samples = vad.front().getSamples();
            vad.pop();
            if (samples.length == 0) {
                continue;
            }
            short[] pcm = toShortArray(samples);
            tasks.add(inferenceExecutor.submit(() -> transcribeVadSegment(pcm)));
        }
    }

    private String transcribeVadSegment(short[] audio) {
        try {
            return transcribeOffline(Engine.VAD_MODE, audio);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to transcribe a VAD segment", error);
            return "";
        }
    }

    private String collectVadResults(
            List<Future<String>> tasks, String currentText, boolean waitForAll) throws Exception {
        String updatedText = currentText;
        while (!tasks.isEmpty() && (waitForAll || tasks.get(0).isDone())) {
            String text = tasks.remove(0).get().trim();
            if (!text.isEmpty()) {
                updatedText = updatedText.isEmpty() ? text : updatedText + "\n" + text;
            }
        }
        if (!updatedText.equals(currentText)) {
            String displayText = updatedText;
            runOnUiThread(() -> displayTranscript(displayText));
        }
        return updatedText;
    }

    private static short[] toShortArray(float[] input) {
        short[] samples = new short[input.length];
        for (int index = 0; index < input.length; index++) {
            float value = Math.max(-1.0f, Math.min(1.0f, input[index]));
            samples[index] = (short) Math.round(value * 32767.0f);
        }
        return samples;
    }

    private String transcribeOffline(Engine engine, short[] audio) {
        OfflineRecognizer recognizer = getOfflineRecognizer(engine);
        OfflineStream stream = recognizer.createStream();
        try {
            float[] samples = toFloatSamples(audio, audio.length);
            stream.acceptWaveform(samples, SAMPLE_RATE);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText().trim();
            return addPunctuationIfNeeded(engine, text);
        } finally {
            stream.release();
        }
    }

    private String addPunctuationIfNeeded(Engine engine, String text) {
        boolean needsPunctuation = engine == Engine.ZIPFORMER
                || engine == Engine.ONLINE_PARAFORMER
                || engine == Engine.OFFLINE_PARAFORMER;
        OfflinePunctuation current = punctuation;
        if (!needsPunctuation || text.isEmpty() || current == null) {
            return text;
        }
        try {
            String punctuated = current.addPunctuation(text);
            return punctuated == null || punctuated.trim().isEmpty()
                    ? text : punctuated.trim();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to restore punctuation", error);
            return text;
        }
    }

    private static float[] toFloatSamples(short[] input, int sampleCount) {
        float[] samples = new float[sampleCount];
        for (int index = 0; index < sampleCount; index++) {
            samples[index] = input[index] / 32768.0f;
        }
        return samples;
    }

    private static void writePcmBytes(
            ByteArrayOutputStream output, short[] input, int sampleCount) {
        for (int index = 0; index < sampleCount; index++) {
            output.write(input[index] & 0xFF);
            output.write((input[index] >> 8) & 0xFF);
        }
    }

    private static short[] toShortArray(ByteArrayOutputStream input) {
        byte[] bytes = input.toByteArray();
        short[] samples = new short[bytes.length / 2];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = (short) ((bytes[index * 2] & 0xFF)
                    | (bytes[index * 2 + 1] << 8));
        }
        return samples;
    }

    private void decodeAvailableFrames(OnlineRecognizer recognizer, OnlineStream stream) {
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }
        isRecording = false;
        processing = true;
        recordingTimer.stop();
        if (recordingEngine == Engine.ANDROID_SYSTEM) {
            statusText.setText(R.string.system_finalizing);
            recordButton.setText(R.string.hold_to_talk);
            updateControls();
            systemSpeechController.stop();
            return;
        }
        if (recordingEngine == Engine.SMART) {
            statusText.setText(R.string.smart_finalizing);
        } else {
            statusText.setText(isOnlineEngine(recordingEngine)
                    ? R.string.finalizing : R.string.offline_finalizing);
        }
        recordButton.setText(R.string.hold_to_talk);
        updateControls();
    }

    private void finishRecording(String result, Throwable error) {
        Engine completedEngine = recordingEngine == null ? selectedEngine : recordingEngine;
        recordingThread = null;
        recordingEngine = null;
        audioRecord = null;

        if (activityDestroyed) {
            releaseRecognizers();
            return;
        }

        processing = false;
        isRecording = false;
        recordingTimer.stop();
        recordButton.setText(R.string.hold_to_talk);

        if (!result.isEmpty()) {
            displayTranscript(result);
        } else {
            displayTranscript(getString(R.string.try_again));
        }

        if (error != null) {
            statusText.setText(R.string.recognition_failed);
        } else if (result.isEmpty()) {
            statusText.setText(R.string.no_speech);
        } else {
            statusText.setText(getString(R.string.engine_finished_format,
                    getString(getEngineLabelRes(completedEngine))));
        }
        updateControls();
    }

    private void resetTranscript() {
        displayedText = "";
        transcriptText.setText(R.string.transcript_placeholder);
    }

    private void displayTranscript(String text) {
        if (activityDestroyed || text == null || text.equals(displayedText)) {
            return;
        }
        displayedText = text;
        transcriptText.setText(text.isEmpty() ? getString(R.string.transcript_placeholder) : text);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showMicrophoneError(Throwable error) {
        Log.e(TAG, "Unable to start the microphone", error);
        statusText.setText(R.string.microphone_failed);
        updateControls();
    }

    private void stopAndReleaseAudioRecord(AudioRecord record) {
        try {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (IllegalStateException error) {
            Log.w(TAG, "AudioRecord was already stopped", error);
        } finally {
            record.release();
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        isRecording = false;
        recordingTimer.stop();

        AudioRecord currentAudioRecord = audioRecord;
        if (currentAudioRecord != null) {
            try {
                currentAudioRecord.stop();
            } catch (IllegalStateException error) {
                Log.w(TAG, "Unable to stop AudioRecord during teardown", error);
            }
        }

        systemSpeechController.destroy();
        modelExecutor.shutdownNow();
        // 活跃识别线程结束时释放，避免 native 识别器在解码过程中被销毁。
        if (recordingThread == null) {
            releaseRecognizers();
        }
        super.onDestroy();
    }

    private synchronized void releaseRecognizers() {
        if (zipformerRecognizer != null) {
            zipformerRecognizer.release();
            zipformerRecognizer = null;
        }
        if (onlineParaformerRecognizer != null) {
            onlineParaformerRecognizer.release();
            onlineParaformerRecognizer = null;
        }
        if (offlineParaformerRecognizer != null) {
            offlineParaformerRecognizer.release();
            offlineParaformerRecognizer = null;
        }
        if (senseVoiceRecognizer != null) {
            senseVoiceRecognizer.release();
            senseVoiceRecognizer = null;
        }
        if (punctuation != null) {
            punctuation.release();
            punctuation = null;
        }
        modelReady = false;
        modelLoading = false;
    }
}
