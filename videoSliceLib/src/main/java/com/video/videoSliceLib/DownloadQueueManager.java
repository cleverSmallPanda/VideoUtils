package com.video.videoSliceLib;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadQueueManager {
    private static final String TAG = "DownloadQueueManager";
    private static DownloadQueueManager instance;
    private Context appContext;
    private ExecutorService singleThreadExecutor;
    private Handler mainHandler;

    private DownloadQueueManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.singleThreadExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 初始化队列管理器（在 Application 或首个 Activity 中调用）
     */
    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new DownloadQueueManager(context);
        }
    }

    public static synchronized DownloadQueueManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("必须首先调用 init(Context)");
        }
        return instance;
    }

    /**
     * 添加下载任务到队列
     *
     * @param videoUrl 视频地址
     * @param callback 回调（将在主线程中执行）
     */
    public void addTask(String videoUrl, final M3U8Downloader.DownloadCallback callback) {
        if (TextUtils.isEmpty(videoUrl)) {
            return;
        }
        if (videoUrl.endsWith(Constant.VIDEO_SLICE_TYPE)) {
            singleThreadExecutor.execute(() -> {
                // 创建下载器实例（每个任务使用独立的下载器）
                M3U8Downloader downloader = new M3U8Downloader(appContext);
                downloader.downloadM3U8ToMP4Sync(videoUrl, new M3U8Downloader.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        // 更新通知
                        mainHandler.post(() -> callback.onProgress(progress));
                    }

                    @Override
                    public void onComplete(String mp4Path) {
                        // 下载完成，显示完成通知（或取消进度通知）
                        mainHandler.post(() -> callback.onComplete(mp4Path));
                    }

                    @Override
                    public void onError(String error) {
                        // 下载失败，显示失败通知
                        mainHandler.post(() -> callback.onError(error));
                    }
                });
            });
        } else {
            singleThreadExecutor.execute(() -> {
                // 创建下载器实例（每个任务使用独立的下载器）
                VideoDownloader downloader = new VideoDownloader(appContext);
                downloader.downloadVideoToMP4(videoUrl, new VideoDownloader.DownloadCallback() {
                    @Override
                    public void onProgress(int progress) {
                        // 更新通知
                        mainHandler.post(() -> callback.onProgress(progress));
                    }

                    @Override
                    public void onComplete(String mp4Path) {
                        // 下载完成，显示完成通知（或取消进度通知）
                        mainHandler.post(() -> callback.onComplete(mp4Path));
                    }

                    @Override
                    public void onError(String error) {
                        // 下载失败，显示失败通知
                        mainHandler.post(() -> callback.onError(error));
                    }
                });
            });
        }

    }


    /**
     * 取消所有任务（可选）
     */
    public void cancelAll() {
        singleThreadExecutor.shutdownNow();
        // 重新创建 executor 以接受新任务
        singleThreadExecutor = Executors.newSingleThreadExecutor();
    }
}