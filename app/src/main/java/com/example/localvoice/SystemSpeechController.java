package com.example.localvoice;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/** Controls Android's on-device SpeechRecognizer without any cloud fallback. */
final class SystemSpeechController {
    interface Listener {
        void onStatus(int messageRes, boolean ready);

        void onDownloadProgress(int percent);

        void onPartialResult(String text);

        void onFinalResult(String text);

        void onSessionError(int error);
    }

    private static final String TAG = "SystemSpeech";
    private static final String LANGUAGE = "cmn-Hans-CN";

    private final Context context;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private boolean ready;
    private boolean sessionActive;
    private boolean destroyed;

    SystemSpeechController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void initialize() {
        destroyed = false;
        ready = false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            listener.onStatus(R.string.system_unavailable, false);
            return;
        }

        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
                recognizer.setRecognitionListener(recognitionListener);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkLanguageSupport();
            } else {
                ready = true;
                listener.onStatus(R.string.system_ready, true);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to initialize on-device SpeechRecognizer", error);
            listener.onStatus(R.string.system_unavailable, false);
        }
    }

    boolean isReady() {
        return ready;
    }

    void start() {
        if (!ready || recognizer == null) {
            throw new IllegalStateException("On-device SpeechRecognizer is not ready");
        }
        sessionActive = true;
        try {
            recognizer.startListening(createRecognitionIntent());
        } catch (Throwable error) {
            sessionActive = false;
            throw error;
        }
    }

    void stop() {
        if (sessionActive && recognizer != null) {
            recognizer.stopListening();
        }
    }

    void cancel() {
        sessionActive = false;
        if (recognizer != null) {
            recognizer.cancel();
        }
    }

    void destroy() {
        destroyed = true;
        ready = false;
        cancel();
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private Intent createRecognitionIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return intent;
    }

    @TargetApi(33)
    private void checkLanguageSupport() {
        recognizer.checkRecognitionSupport(
                createRecognitionIntent(),
                context.getMainExecutor(),
                new RecognitionSupportCallback() {
                    @Override
                    public void onSupportResult(RecognitionSupport support) {
                        if (destroyed) {
                            return;
                        }
                        Log.i(TAG, "Installed on-device languages: "
                                + support.getInstalledOnDeviceLanguages()
                                + ", downloadable on-device languages: "
                                + support.getSupportedOnDeviceLanguages()
                                + ", pending on-device languages: "
                                + support.getPendingOnDeviceLanguages()
                                + ", online languages: " + support.getOnlineLanguages());
                        if (containsChinese(support.getInstalledOnDeviceLanguages())) {
                            ready = true;
                            listener.onStatus(R.string.system_ready, true);
                        } else if (containsChinese(support.getSupportedOnDeviceLanguages())
                                || containsChinese(support.getPendingOnDeviceLanguages())) {
                            if (Build.VERSION.SDK_INT >= 34) {
                                downloadLanguageModel();
                            } else {
                                listener.onStatus(R.string.system_language_unavailable, false);
                            }
                        } else if (containsChinese(support.getOnlineLanguages())) {
                            ready = false;
                            listener.onStatus(R.string.system_only_online, false);
                        } else {
                            ready = false;
                            listener.onStatus(R.string.system_language_unsupported, false);
                        }
                    }

                    @Override
                    public void onError(int error) {
                        Log.e(TAG, "Unable to check language support: " + error);
                        ready = false;
                        listener.onStatus(R.string.system_check_failed, false);
                    }
                });
    }

    @TargetApi(34)
    private void downloadLanguageModel() {
        ready = false;
        listener.onStatus(R.string.system_downloading, false);
        recognizer.triggerModelDownload(
                createRecognitionIntent(),
                context.getMainExecutor(),
                new ModelDownloadListener() {
                    @Override
                    public void onProgress(int completedPercent) {
                        if (!destroyed) {
                            listener.onDownloadProgress(completedPercent);
                        }
                    }

                    @Override
                    public void onScheduled() {
                        if (!destroyed) {
                            listener.onStatus(R.string.system_download_pending, false);
                        }
                    }

                    @Override
                    public void onSuccess() {
                        if (!destroyed) {
                            ready = true;
                            listener.onStatus(R.string.system_ready, true);
                        }
                    }

                    @Override
                    public void onError(int error) {
                        Log.e(TAG, "Unable to download the Chinese model: " + error);
                        ready = false;
                        listener.onStatus(R.string.system_download_failed, false);
                    }
                });
    }

    private static boolean containsChinese(List<String> languages) {
        return languages.contains("cmn-Hans-CN")
                || languages.contains("zh-Hans-CN")
                || languages.contains("zh-CN");
    }

    private static String firstResult(Bundle results) {
        if (results == null) {
            return "";
        }
        ArrayList<String> texts = results.getStringArrayList(
                SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty() || texts.get(0) == null) {
            return "";
        }
        return texts.get(0).trim();
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            sessionActive = false;
            listener.onSessionError(error);
        }

        @Override
        public void onResults(Bundle results) {
            if (sessionActive) {
                sessionActive = false;
                listener.onFinalResult(firstResult(results));
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (sessionActive) {
                listener.onPartialResult(firstResult(partialResults));
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };
}
