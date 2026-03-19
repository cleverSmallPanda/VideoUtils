package com.video.videoSliceLib;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.FFmpeg;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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
        File tempFile = null;
        File outputFile = null;
        try {
            // 1. 生成临时文件（用于存放下载的原始数据）
            String tempFileName = "temp_" + System.currentTimeMillis() + ".tmp";
            tempFile = new File(outputDir, tempFileName);
            // 确保父目录存在
            tempFile.getParentFile().mkdirs();

            // 2. 下载视频文件，并获取 Content-Type 和实际大小（用于进度）
            callback.onProgress(1);
            long totalBytes = downloadFile(videoUrl, tempFile, new ProgressListener() {
                @Override
                public void onProgress(long downloaded, long total) {
                    if (total > 0) {
                        int progress = (int) (downloaded * 90 / total); // 下载占 90%
                        callback.onProgress(progress);
                    }
                }
            });
            if (totalBytes <= 0) {
                callback.onError("下载失败或文件为空");
                return;
            }
            callback.onProgress(90);

            // 3. 确定输出文件路径（最终 MP4）
            String outputFileName = "video_" + System.currentTimeMillis() + ".mp4";
            outputFile = new File(outputDir, outputFileName);

            // 4. 判断是否需要转码：如果文件已经是 MP4 格式，直接重命名；否则调用 FFmpeg 转码
            boolean needTranscode = !isFileMP4(tempFile, videoUrl);
            if (needTranscode) {
                Log.d(TAG, "需要转码为 MP4");
                boolean transcodeSuccess = transcodeToMP4(tempFile, outputFile);
                if (!transcodeSuccess) {
                    callback.onError("视频转码失败");
                    return;
                }
            } else {
                Log.d(TAG, "文件已是 MP4，直接移动");
                if (!tempFile.renameTo(outputFile)) {
                    // 重命名失败则尝试复制（跨分区情况）
                    if (!copyFile(tempFile, outputFile)) {
                        callback.onError("文件移动失败");
                        return;
                    }
                }
            }

            // 5. 清理临时文件
            if (tempFile.exists()) {
                tempFile.delete();
            }

            callback.onProgress(100);
            callback.onComplete(outputFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "处理失败", e);
            callback.onError(e.getMessage());
            // 清理残留的临时文件
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            if (outputFile != null && outputFile.exists()) {
                outputFile.delete();
            }
        }
    }

    // ==================== 下载核心（使用 OkHttp，支持进度） ====================
    private long downloadFile(String url, File targetFile, ProgressListener listener) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Android)")
                .build();

        int maxRetries = 3;
        int retryCount = 0;
        while (retryCount < maxRetries) {
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("HTTP " + response.code());
                }

                long contentLength = response.body().contentLength();
                if (contentLength == 0) {
                    throw new IOException("Content-Length 为 0");
                }

                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalRead = 0;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        if (contentLength > 0) {
                            listener.onProgress(totalRead, contentLength);
                        }
                    }
                    fos.flush();
                }

                // 验证文件大小
                long fileSize = targetFile.length();
                if (fileSize == 0) {
                    targetFile.delete();
                    throw new IOException("下载文件为空");
                }
                if (contentLength > 0 && fileSize != contentLength) {
                    targetFile.delete();
                    throw new IOException("文件大小不匹配，预期 " + contentLength + "，实际 " + fileSize);
                }
                return fileSize;
            } catch (IOException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    throw e;
                } else {
                    Log.w(TAG, "下载失败，重试 " + retryCount + "/" + maxRetries);
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
        return -1;
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
        Config.enableLogCallback(message -> {
            String text = message.getText();
            log.append(text);
            Log.d(TAG, "FFmpeg: " + text);
        });

        int rc = FFmpeg.execute(cmd);
        Config.enableLogCallback(null);

        if (rc == Config.RETURN_CODE_SUCCESS) {
            Log.i(TAG, "转码成功: " + outputFile.getAbsolutePath());
            return true;
        } else {
            Log.e(TAG, "转码失败，返回码: " + rc);
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

    // ==================== 内部进度监听接口 ====================
    private interface ProgressListener {
        void onProgress(long downloaded, long total);
    }
}
