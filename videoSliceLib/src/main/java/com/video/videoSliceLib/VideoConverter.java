package com.video.videoSliceLib;

import android.content.Context;

import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.arthenica.mobileffmpeg.Statistics;
import com.arthenica.mobileffmpeg.StatisticsCallback;

import java.io.File;

public class VideoConverter {
    public static final int RETURN_CODE_SUCCESS = 0;

    public interface ConversionCallback {
        void onSuccess(File m3u8File, File tsDir);
        void onProgress(float progress);
        void onFailure(String error);
    }

    /**
     * 将视频转换为M3U8格式
     * @param context 上下文
     * @param inputPath 输入视频路径
     * @param outputDir 输出目录
     * @param videoId 视频ID，用于生成文件名
     * @param segmentTime 分片时长（秒）
     * @param duration 视频总时长（毫秒），用于进度计算
     * @param callback 回调接口
     */
    public static void convertToM3U8(Context context,
                                     String inputPath,
                                     String outputDir,
                                     String videoId,
                                     int segmentTime,
                                     long duration,
                                     ConversionCallback callback) {
        try {
            // 创建输出目录
            File outputDirFile = new File(outputDir);
            if (!outputDirFile.exists()) {
                outputDirFile.mkdirs();
            }

            String m3u8Path = outputDir + "/" + videoId + Constant.VIDEO_SLICE_TYPE;
            String thumbnailPath = outputDir + "/" + videoId + ".jpg";

            // 启用统计信息回调
            Config.enableStatisticsCallback(new StatisticsCallback() {
                @Override
                public void apply(Statistics statistics) {
                    float progress = (float) (statistics.getTime() *100f / duration);
                    callback.onProgress(progress);
                }
            });

            // FFmpeg命令参数
            String[] cmd = new String[]{
                    "-i", inputPath,
                    // 视频流处理 - 生成M3U8
                    "-map", "0:v:0",      // 第一个视频流
                    "-c:v", "libx264",
                    "-preset", "medium",   // 编码速度与质量的平衡
                    "-crf", "23",         // 视频质量（18-28，越小质量越高）
                    "-sc_threshold", "0",
                    "-g", "60",           // 关键帧间隔
                    "-keyint_min", "60",
                    "-hls_time", String.valueOf(segmentTime),
                    "-hls_list_size", "0",
                    "-hls_segment_filename", outputDir + "/segment_%03d.ts",
                    "-f", "hls",
                    m3u8Path,

                    // 缩略图处理
                    "-map", "0:v:0",      // 再次使用第一个视频流
                    "-frames:v", "1",     // 只取1帧
                    "-ss", "00:00:01",    // 从第1秒开始取（避免黑屏）
                    "-vf", "scale='min(1080,iw)':-2", // 缩放到宽度1080，高度按比例
                    "-q:v", "2",          // 图片质量（2-31，越小质量越高）
                    "-f", "image2",
                    thumbnailPath
            };

            long exeId = FFmpeg.executeAsync(cmd, (executionId, returnCode) -> {
                Config.enableStatisticsCallback(null); // 移除回调

                if (returnCode == RETURN_CODE_SUCCESS) {
                    File m3u8File = new File(m3u8Path);
                    callback.onSuccess(m3u8File, outputDirFile);
                } else {
                    callback.onFailure("视频转换失败，错误码: " + returnCode);
                }
            });
        } catch (Exception e) {
            callback.onFailure(e.getMessage());
        }
    }

    public static String buildCommandString(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (arg.contains(" ") || arg.contains("\t") || arg.contains("\"")) {
                // 参数包含空格或引号，用双引号包围，并转义内部的双引号
                String escaped = arg.replace("\"", "\\\"");
                sb.append('"').append(escaped).append('"');
            } else {
                sb.append(arg);
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * 获取视频时长（毫秒）
     * @param videoPath 视频路径
     * @return
     */
    public static long getVideoDuration(String videoPath) {
        try {
            String[] cmd = {
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_format",
                    videoPath
            };
            String command = String.join(" ", cmd);

            // 同步执行 ffprobe
//            FFprobeSession session = FFprobeKit.execute(command);
//            if (ReturnCode.isSuccess(session.getReturnCode())) {
//                // 获取所有日志并拼接成字符串
//                List<Log> logs = session.getAllLogs();
//                StringBuilder output = new StringBuilder();
//                for (Log log : logs) {
//                    output.append(log.getMessage());
//                }
//                String jsonOutput = output.toString();
//
//                // 解析 JSON
//                JSONObject format = new JSONObject(jsonOutput).getJSONObject("format");
//                double durationSec = format.getDouble("duration");
//                return (long) (durationSec * 1000);
//            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
}