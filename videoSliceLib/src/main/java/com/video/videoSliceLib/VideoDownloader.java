package com.video.videoSliceLib;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.ReturnCode;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.FileCallback;
import com.lzy.okgo.model.Progress;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * 常规视频下载工具（支持 mp4、mov、avi 等格式）
 * 最终输出为标准 MP4 文件
 */
public class VideoDownloader {
    private static final String TAG = "VideoDownloader";
    private final Context context;
    private final String outputDir;      // 临时文件及最终输出目录

    // 下载回调接口
    public interface DownloadCallback {
        void onProgress(int progress);        // 0-100

        void onComplete(String mp4Path);      // 返回最终 MP4 文件路径

        void onError(String error);            // 错误信息
    }

    public VideoDownloader(Context context) {
        this.context = context;
        // 使用公共下载目录下的 video_temp/ 作为工作目录
        this.outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .getPath() + "/video_temp/";
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    // ==================== 对外公开的异步方法 ====================
    public void downloadVideoToMP4(String videoUrl, DownloadCallback callback) {
        new Thread(() -> downloadVideoToMP4Sync(videoUrl, callback)).start();
    }

    // ==================== 同步核心方法 ====================
    public void downloadVideoToMP4Sync(String videoUrl, final DownloadCallback callback) {
        // 生成临时文件和最终输出文件（名称基于时间戳）
        String timestamp = String.valueOf(System.currentTimeMillis());
        File tempFile = new File(outputDir, "temp_" + timestamp + ".tmp");
        File finalFile = new File(outputDir, "video_" + timestamp + ".mp4");

        // 用于同步等待下载完成的工具
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] downloadSuccess = {false};
        final String[] errorMsg = {null};

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    // 添加浏览器风格的请求头
                    okhttp3.Request original = chain.request();
                    okhttp3.Request request = original.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "video/webm,video/mp4,video/*;q=0.9,*/*;q=0.8")
                            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                            .header("Range", "bytes=0-") // 可选，确保从开头下载
                            .build();
                    return chain.proceed(request);
                })
                .build();
        // 发起异步下载请求
        OkGo.<File>get(videoUrl)
                .tag(this)
                .client(client)
                .execute(new FileCallback(outputDir, tempFile.getName()) {
                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<File> response) {
                        downloadSuccess[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onError(com.lzy.okgo.model.Response<File> response) {
                        super.onError(response);
                        downloadSuccess[0] = false;
                        errorMsg[0] = response.getException() != null
                                ? response.getException().getMessage()
                                : "下载失败";
                        latch.countDown();
                    }

                    @Override
                    public void downloadProgress(Progress progress) {
                        super.downloadProgress(progress);
                        int progressInt = (int) (progress.fraction * 100);
                        callback.onProgress(progressInt);
                    }
                });

        // 等待下载线程完成
        try {
            latch.await();
        } catch (InterruptedException e) {
            callback.onError("下载被中断");
            return;
        }

        // 下载失败处理
        if (!downloadSuccess[0]) {
            callback.onError(errorMsg[0] != null ? errorMsg[0] : "未知下载错误");
            return;
        }

        // 判断已下载的文件是否为 MP4 格式
        if (isFileMP4(tempFile, videoUrl)) {
            // 已经是 MP4，直接复制到最终路径
            boolean copied = copyFile(tempFile, finalFile);
            tempFile.delete(); // 删除临时文件
            if (copied) {
                callback.onComplete(finalFile.getAbsolutePath());
            } else {
                callback.onError("复制文件失败");
            }
        } else {
            // 需要转码为 MP4
            boolean transcoded = transcodeToMP4(tempFile, finalFile);
            tempFile.delete(); // 删除临时文件
            if (transcoded) {
                callback.onComplete(finalFile.getAbsolutePath());
            } else {
                callback.onError("转码失败");
            }
        }
    }

    // ==================== 下载核心（使用 OkHttp，支持进度） ====================
    private void downloadFile(String url, File targetFile, DownloadCallback callback) {

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        OkGo.<File>get(url)
                .client(client)
                .tag(this)                       // 用于取消下载
                .execute(new FileCallback(outputDir, targetFile.getName()) {
                    @Override
                    public void onSuccess(com.lzy.okgo.model.Response<File> response) {

                    }

                    @Override
                    public void onError(com.lzy.okgo.model.Response<File> response) {
                        super.onError(response);

                    }

                    @Override
                    public void downloadProgress(Progress progress) {
                        super.downloadProgress(progress);
                        long currentSize = progress.currentSize;   // 已下载大小
                        long totalSize = progress.totalSize;       // 总大小
                        float fraction = progress.fraction;        // 下载进度 (0-1)
                        int progressInt = (int) (fraction * 100);         // 下载进度百分比
                        callback.onProgress(progressInt);

                    }
                });

    }

    // ==================== 判断文件是否为 MP4 格式 ====================
    private boolean isFileMP4(File file, String originalUrl) {
        // 1. 通过文件扩展名初步判断
        String urlLower = originalUrl.toLowerCase();
        if (urlLower.contains(".mp4") || urlLower.contains(".mp4?")) {
            return true;
        }
        // 2. 可以通过文件头魔数判断（可选），这里简单返回 false 表示需要转码
        // 实际可读取文件前几个字节判断 ftyp 等，为简化直接转码
        return false;
    }

    // ==================== 使用 FFmpeg 转码为 MP4 ====================
    private boolean transcodeToMP4(File inputFile, File outputFile) {
        // 构建 FFmpeg 命令：输入文件 -> libx264 + aac 编码输出 MP4
        // 如果希望尝试流复制（更快但不一定兼容），可以改用 "-c copy"，但这里用标准编码保证兼容性
        String[] cmd = {
                "-i", inputFile.getAbsolutePath(),
                "-c:v", "libx264",
                "-c:a", "aac",
                "-y",                          // 覆盖输出文件
                outputFile.getAbsolutePath()
        };

        Log.d(TAG, "FFmpeg 转码命令: " + String.join(" ", cmd));

        // 启用日志回调（可选）
        StringBuilder log = new StringBuilder();
        FFmpegKitConfig.enableLogCallback(new LogCallback() {
            @Override
            public void apply(com.arthenica.ffmpegkit.Log log) {
                String text = log.getMessage();
                Log.d(TAG, "FFmpeg: " + text);
            }
        });

        String cmdString = VideoConverter.buildCommandString(cmd);
        FFmpegSession session = FFmpegKit.execute(cmdString);
        ReturnCode returnCode = session.getReturnCode();
        // 5. 清理回调
        FFmpegKitConfig.enableLogCallback(null);

        if (ReturnCode.isSuccess(returnCode)) {
            Log.i(TAG, "转码成功: " + outputFile.getAbsolutePath());
            return true;
        } else {
            Log.e(TAG, "转码失败，返回码: " + returnCode.getValue());
            Log.e(TAG, "FFmpeg 输出: " + log.toString());
            return false;
        }


    }

    // ==================== 辅助：复制文件（用于跨分区移动） ====================
    private boolean copyFile(File src, File dst) {
        try (InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "复制文件失败", e);
            return false;
        }
    }

}
