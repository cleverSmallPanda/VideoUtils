package com.video.videoSliceLib;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.LogCallback;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Response;

public class M3U8Downloader {
    public static final int RETURN_CODE_SUCCESS = 0;

    private static final String TAG = "M3U8Downloader";
    private Context context;
    private String baseUrl;
    private String outputDir;

    // 回调接口
    public interface DownloadCallback {
        void onProgress(int progress);

        void onComplete(String mp4Path);

        void onError(String error);
    }

    public M3U8Downloader(Context context) {
        this.context = context;
//        this.outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).getPath() + "/m3u8_temp/";
        this.outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getPath() + "/m3u8_temp/";

        // 创建临时目录
        File dir = new File(outputDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * 同步下载 M3U8 并转换为 MP4（阻塞直到完成）
     * 供队列管理器使用
     */
    public void downloadM3U8ToMP4Sync(String m3u8Url, final DownloadCallback callback) {
        try {
            // 1. 下载 m3u8 文件
            String m3u8Content = downloadM3U8File(m3u8Url);
            if (m3u8Content == null) {
                callback.onError("下载 m3u8 文件失败");
                return;
            }

            // 2. 解析 ts 文件列表
            List<String> tsUrls = parseTSFiles(m3u8Content, m3u8Url);
            if (tsUrls.isEmpty()) {
                callback.onError("解析 ts 文件失败");
                return;
            }

            callback.onProgress(5);

            // 3. 批量下载 ts 文件（同步等待）
            List<File> tsFiles = downloadTSFiles(tsUrls, new ProgressListener() {
                @Override
                public void onProgress(int downloaded, int total) {
                    int progress = 5 + (downloaded * 90 / total);
                    callback.onProgress(progress);
                }
            });

            callback.onProgress(95);

            // 4. 合并 ts 文件为 mp4
            String mp4Path = mergeTSFilesToMP4(tsFiles);
            if (mp4Path == null) {
                callback.onError("合并 MP4 失败");
                return;
            }

            // 5. 清理临时文件
            cleanupTempFiles(tsFiles);

            callback.onProgress(100);
            callback.onComplete(mp4Path);

        } catch (Exception e) {
            Log.e(TAG, "下载失败", e);
            callback.onError(e.getMessage());
        }
    }

    /**
     * 主下载方法
     */
    public void downloadM3U8ToMP4(String m3u8Url, final DownloadCallback callback) {
        new Thread(() -> {
            try {
                // 1. 下载 m3u8 文件
                String m3u8Content = downloadM3U8File(m3u8Url);
                if (m3u8Content == null) {
                    callback.onError("下载 m3u8 文件失败");
                    return;
                }

                // 2. 解析 ts 文件列表
                List<String> tsUrls = parseTSFiles(m3u8Content, m3u8Url);
                if (tsUrls.isEmpty()) {
                    callback.onError("解析 ts 文件失败");
                    return;
                }

                Log.d(TAG, "找到 " + tsUrls.size() + " 个 ts 文件");
                callback.onProgress(5);

                // 3. 批量下载 ts 文件
                List<File> tsFiles = downloadTSFiles(tsUrls, new ProgressListener() {
                    @Override
                    public void onProgress(int downloaded, int total) {
                        int progress = 5 + (downloaded * 90 / total);
                        callback.onProgress(progress);
                        Log.d(TAG, "下载进度： " + progress);
                    }
                });

                callback.onProgress(95);

                Log.d(TAG, "开始合并视频");
                // 4. 合并 ts 文件为 mp4
                String mp4Path = mergeTSFilesToMP4(tsFiles);

                // 5. 清理临时文件
                cleanupTempFiles(tsFiles);

                callback.onProgress(100);
                callback.onComplete(mp4Path);

            } catch (Exception e) {
                Log.e(TAG, "下载失败", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * 下载 m3u8 文件内容
     */
    private String downloadM3U8File(String m3u8Url) {
        try {
            // 使用 HttpURLConnection 直接下载（简单方式）
            URL url = new URL(m3u8Url);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            // 处理阿里云 OSS 可能需要的鉴权（如果 URL 已包含签名可忽略）
            Map<String, String> headers = getOSSAuthHeaders(m3u8Url);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                InputStream inputStream = connection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();
                return content.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "下载 m3u8 失败", e);
        }
        return null;
    }

    /**
     * 解析 ts 文件 URL 列表
     */
    private List<String> parseTSFiles(String m3u8Content, String m3u8Url) {
        List<String> tsUrls = new ArrayList<>();

        try {
            // 获取基础 URL（用于相对路径）
            URL base = new URL(m3u8Url);
            String basePath = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);

            String[] lines = m3u8Content.split("\n");
            for (String line : lines) {
                line = line.trim();
                // 跳过注释行
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }

                // 处理 ts 文件
                if (line.endsWith(".ts")) {
                    String tsUrl;
                    if (line.startsWith("http")) {
                        // 绝对路径
                        tsUrl = line;
                    } else if (line.startsWith("/")) {
                        // 相对于域名的路径
                        tsUrl = base.getProtocol() + "://" + base.getHost() + line;
                    } else {
                        // 相对路径
                        tsUrl = basePath + line;
                    }
                    tsUrls.add(tsUrl);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "解析 ts 文件失败", e);
        }

        return tsUrls;
    }

    /**
     * 批量下载 ts 文件（使用 Fetch2）
     */
    private List<File> downloadTSFiles(List<String> tsUrls, ProgressListener progressListener) {
        final List<File> tsFiles = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(tsUrls.size());
        final AtomicInteger completedCount = new AtomicInteger(0);

        // 使用线程池控制并发
        ExecutorService executor = Executors.newFixedThreadPool(1);

        for (int i = 0; i < tsUrls.size(); i++) {
            final int index = i;
            final String tsUrl = tsUrls.get(i);
            final String tsFilePath = outputDir + "segment_" + String.format("%05d", i) + ".ts";

            executor.execute(() -> {
                try {
                    downloadSingleTSWithOkHttp(tsUrl, tsFilePath, index);
                    tsFiles.add(new File(tsFilePath));

                    // 更新进度
                    int completed = completedCount.incrementAndGet();
                    progressListener.onProgress(completed, tsUrls.size());

                } catch (Exception e) {
                    Log.e(TAG, "下载 ts 文件失败: " + tsUrl, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await(); // 等待所有下载完成
        } catch (InterruptedException e) {
            Log.e(TAG, "等待下载完成时被中断", e);
        }

        executor.shutdown();
        return tsFiles;
    }

    private boolean downloadSingleTSWithOkHttp(String tsUrl, String filePath, int index) {

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS) // 长超时，避免大文件下载中断
                .build();

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(tsUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Android)")
                .build();

        int maxRetries = 3;
        int retryCount = 0;
        while (retryCount < maxRetries) {
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "HTTP错误 " + index + ": " + response.code());
                    return false;
                }

                // 获取内容长度（可能为-1）
                long contentLength = response.body().contentLength();
                Log.d(TAG, "文件 " + index + " 预期大小: " + contentLength);

                File file = new File(filePath);
                // 确保父目录存在
                file.getParentFile().mkdirs();

                try (FileOutputStream fos = new FileOutputStream(file);
                     InputStream is = response.body().byteStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalRead = 0;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                    }
                    fos.flush();
                }

                // 验证文件是否非空
                if (file.length() == 0) {
                    file.delete();
                    Log.e(TAG, "文件 " + index + " 大小为0");
                    return false;
                }

                // 如果服务器返回了Content-Length，验证大小是否匹配
                if (contentLength > 0 && file.length() != contentLength) {
                    Log.e(TAG, "文件大小不匹配 " + index + ": 预期 " + contentLength + ", 实际 " + file.length());
                    file.delete();
                    return false;
                }

                Log.d(TAG, "文件 " + index + " 下载成功，大小: " + file.length());
                return true;
            } catch (IOException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    Log.e(TAG, "下载失败 " + index + "，重试耗尽: " + e.getMessage());
                } else {
                    Log.w(TAG, "下载失败 " + index + "，重试 " + retryCount + "/" + maxRetries);
                    try {
                        Thread.sleep(2000); // 重试前等待2秒
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        return false;
    }

    public String mergeTSFilesToMP4(List<File> tsFiles) {
        String outputMp4Path = outputDir + "output_" + System.currentTimeMillis() + ".mp4";

        // 1. 创建一个临时文件列表（ffmpeg concat 要求）
        File listFile = new File(outputDir, "filelist_" + System.currentTimeMillis() + ".txt");
        try (FileWriter writer = new FileWriter(listFile)) {
            for (File tsFile : tsFiles) {
                // 使用绝对路径，并用单引号括起来，以支持特殊字符
                writer.write("file '" + tsFile.getAbsolutePath() + "'\n");
            }
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "创建文件列表失败", e);
            return null;
        }

        // 2. 构建 ffmpeg 命令
        // -f concat: 使用 concat demuxer
        // -safe 0: 允许使用绝对路径
        // -i 列表文件
        // -c copy: 流复制，不重新编码
        // -bsf:a aac_adtstoasc: 修正音频（如果音频是 AAC）
        // -y: 覆盖输出文件
        String[] cmd = {
                "-f", "concat",
                "-safe", "0",
                "-i", listFile.getAbsolutePath(),
                "-c", "copy",
                "-bsf:a", "aac_adtstoasc",
                "-y",
                outputMp4Path
        };

        Log.d(TAG, "执行命令: " + String.join(" ", cmd));

        // 3. 设置日志回调以捕获详细输出
        StringBuilder logBuilder = new StringBuilder();
        FFmpegKitConfig.enableLogCallback(new LogCallback() {
            @Override
            public void apply(com.arthenica.ffmpegkit.Log log) {
                String text = log.getMessage();
                logBuilder.append(text);
                Log.d(TAG, "FFmpeg: " + text); // 实时打印，便于调试
            }
        });
        // 4. 同步执行
        String cmdString = VideoConverter.buildCommandString(cmd);

        FFmpegSession session = FFmpegKit.execute(cmdString);
        ReturnCode returnCode = session.getReturnCode();
        // 5. 清理回调
        FFmpegKitConfig.enableLogCallback(null);

        // 6. 删除临时列表文件
        listFile.delete();

        if (ReturnCode.isSuccess(returnCode)) {
            Log.i(TAG, "合并成功: " + outputMp4Path);
            return outputMp4Path;
        } else {
            Log.e(TAG, "合并失败，返回值: " + returnCode.getValue());
            Log.e(TAG, "完整输出: " + logBuilder.toString());
            // 删除可能损坏的输出文件
            new File(outputMp4Path).delete();
            return null;
        }
    }


    /**
     * 获取阿里云 OSS 认证请求头（如果需要）
     */
    private Map<String, String> getOSSAuthHeaders(String url) {
        Map<String, String> headers = new HashMap<>();

        // 如果 URL 已包含签名参数，通常不需要额外头信息
        // 如果需要自定义头，可以在这里添加
        // 例如：headers.put("Authorization", "OSS your_access_key_id:signature");

        // 设置 User-Agent
        headers.put("User-Agent", "Mozilla/5.0 (Android)");

        return headers;
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFiles(List<File> tsFiles) {
        for (File file : tsFiles) {
            if (file.exists()) {
                file.delete();
            }
        }
        // 清理 m3u8 文件等其他临时文件
        File tempDir = new File(outputDir);
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".ts") || file.getName().endsWith(Constant.VIDEO_SLICE_TYPE)) {
                    file.delete();
                }
            }
        }
    }

    /**
     * 进度监听接口
     */
    interface ProgressListener {
        void onProgress(int downloaded, int total);
    }

}