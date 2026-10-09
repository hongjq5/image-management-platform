package com.yupi.yupicturebackend.manager.upload;

import cn.hutool.core.io.FileUtil;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.yupi.yupicturebackend.config.CosClientConfig;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.manager.CosManager;
import com.yupi.yupicturebackend.model.dto.file.UploadPictureResult;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.util.*;

/** Validates actual image bytes before sending them to object storage. */
@Slf4j
public abstract class PictureUploadTemplate {
    protected static final long MAX_FILE_SIZE = 2 * 1024 * 1024;
    private static final long MAX_PIXELS = 25_000_000;
    @Resource
    private CosClientConfig cosClientConfig;
    @Resource
    private CosManager cosManager;

    public UploadPictureResult uploadPicture(Object inputSource, String uploadPathPrefix) {
        validPicture(inputSource);
        if (uploadPathPrefix == null || !uploadPathPrefix.matches("(public|space)/[0-9]+")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "上传目录错误");
        }
        String originalFilename = getOriginFilename(inputSource);
        File file = null;
        String uploadKey = null;
        boolean uploadAttempted = false;
        try {
            file = File.createTempFile("picture-", ".upload");
            processFile(inputSource, file);
            String format = validateImage(file);
            uploadKey = uploadPathPrefix + "/" + UUID.randomUUID() + "." + format;
            uploadAttempted = true;
            PutObjectResult result = cosManager.putPictureObject(uploadKey, file);
            ImageInfo info = result.getCiUploadResult().getOriginalInfo().getImageInfo();
            if (info == null || info.getWidth() <= 0 || info.getHeight() <= 0
                    || (long) info.getWidth() * info.getHeight() > MAX_PIXELS) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片解码结果无效");
            }
            List<CIObject> objects = result.getCiUploadResult().getProcessResults() == null
                    ? Collections.emptyList() : result.getCiUploadResult().getProcessResults().getObjectList();
            UploadPictureResult picture = new UploadPictureResult();
            picture.setPicName(FileUtil.mainName(originalFilename));
            picture.setPicColor(info.getAve());
            if (objects != null && !objects.isEmpty()) {
                CIObject compressed = objects.get(0);
                CIObject thumbnail = objects.size() > 1 ? objects.get(1) : compressed;
                picture.setUrl(objectUrl(compressed.getKey()));
                picture.setThumbnailUrl(objectUrl(thumbnail.getKey()));
                picture.setPicSize(compressed.getSize().longValue());
                picture.setPicWidth(compressed.getWidth());
                picture.setPicHeight(compressed.getHeight());
                picture.setPicFormat(compressed.getFormat());
                // COS processing retains the original; it is not referenced after conversion.
                if (!uploadKey.equals(compressed.getKey()) && !uploadKey.equals(thumbnail.getKey())) {
                    deleteQuietly(uploadKey);
                }
            } else {
                picture.setUrl(objectUrl(uploadKey));
                picture.setThumbnailUrl(picture.getUrl());
                picture.setPicSize(file.length());
                picture.setPicWidth(info.getWidth());
                picture.setPicHeight(info.getHeight());
                picture.setPicFormat(info.getFormat());
            }
            if (picture.getPicWidth() <= 0 || picture.getPicHeight() <= 0 || picture.getPicSize() <= 0) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片处理结果无效");
            }
            picture.setPicScale(Math.round(picture.getPicWidth() * 100.0 / picture.getPicHeight()) / 100.0);
            return picture;
        } catch (Exception exception) {
            if (uploadAttempted) {
                String stem = uploadKey.substring(0, uploadKey.lastIndexOf('.'));
                deleteQuietly(uploadKey);
                deleteQuietly(stem + ".webp");
                deleteQuietly(stem + "_thumbnail." + FileUtil.getSuffix(uploadKey));
            }
            if (exception instanceof BusinessException) throw (BusinessException) exception;
            log.error("图片上传失败", exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传失败");
        } finally {
            deleteTempFile(file);
        }
    }

    private String objectUrl(String key) {
        return cosClientConfig.getHost().replaceAll("/+$", "") + "/" + cosManager.getObjectKey(key);
    }

    static void copyBounded(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        int length;
        while ((length = input.read(buffer)) != -1) {
            total += length;
            if (total > MAX_FILE_SIZE || System.nanoTime() > deadline) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片超过 2MB 或下载超时");
            }
            output.write(buffer, 0, length);
        }
        if (total == 0) throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片不能为空");
    }

    static String validateImage(File file) throws IOException {
        if (file.length() == 0 || file.length() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片大小必须在 1 字节到 2MB 之间");
        }
        byte[] header = java.nio.file.Files.readAllBytes(file.toPath());
        if (header.length >= 12 && tag(header, 0).equals("RIFF") && tag(header, 8).equals("WEBP")) {
            validateWebp(header);
            return "webp";
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持真实 JPEG、PNG 或静态 WebP 图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Arrays.asList("jpeg", "jpg", "png").contains(format)) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "仅支持 JPEG、PNG 或静态 WebP 图片");
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片尺寸过大");
                }
                if (reader.read(0) == null) throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片损坏");
                return format.equals("jpg") ? "jpeg" : format;
            } catch (IOException exception) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片损坏");
            } finally {
                reader.dispose();
            }
        }
    }

    /** Bounded container/frame validation; COS must subsequently decode the complete WebP. */
    private static void validateWebp(byte[] bytes) {
        if (littleEndian(bytes, 4, 4) + 8 != bytes.length) throw invalidWebp();
        boolean image = false;
        int offset = 12;
        while (offset < bytes.length) {
            if (offset + 8 > bytes.length) throw invalidWebp();
            String type = tag(bytes, offset);
            long size = littleEndian(bytes, offset + 4, 4);
            long next = offset + 8L + size + (size & 1);
            if (next > bytes.length || size < 0) throw invalidWebp();
            int start = offset + 8;
            long width = 0, height = 0;
            if (type.equals("VP8 ")) {
                if (image || size < 10 || (bytes[start] & 1) != 0 || (bytes[start + 3] & 255) != 0x9d
                        || bytes[start + 4] != 1 || bytes[start + 5] != 0x2a) throw invalidWebp();
                width = littleEndian(bytes, start + 6, 2) & 0x3fff;
                height = littleEndian(bytes, start + 8, 2) & 0x3fff;
                image = true;
            } else if (type.equals("VP8L")) {
                if (image || size < 5 || bytes[start] != 0x2f) throw invalidWebp();
                long bits = littleEndian(bytes, start + 1, 4);
                if ((bits >>> 29) != 0) throw invalidWebp();
                width = (bits & 0x3fff) + 1;
                height = ((bits >>> 14) & 0x3fff) + 1;
                image = true;
            } else if (type.equals("VP8X")) {
                if (size != 10 || (bytes[start] & 2) != 0) throw invalidWebp();
                width = littleEndian(bytes, start + 4, 3) + 1;
                height = littleEndian(bytes, start + 7, 3) + 1;
            } else if (type.equals("ANIM") || type.equals("ANMF")) {
                throw invalidWebp();
            }
            if ((type.equals("VP8 ") || type.equals("VP8L") || type.equals("VP8X"))
                    && (width <= 0 || height <= 0 || width * height > MAX_PIXELS)) throw invalidWebp();
            offset = (int) next;
        }
        if (!image) throw invalidWebp();
    }

    private static String tag(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static long littleEndian(byte[] bytes, int offset, int count) {
        long value = 0;
        for (int index = 0; index < count; index++) value |= (long) (bytes[offset + index] & 255) << (index * 8);
        return value;
    }

    private static BusinessException invalidWebp() {
        return new BusinessException(ErrorCode.PARAMS_ERROR, "WebP 图片结构、大小或尺寸无效，仅支持静态图片");
    }

    private void deleteQuietly(String key) {
        try {
            cosManager.deleteObject(key);
        } catch (Exception exception) {
            log.warn("图片对象清理失败，需重试: {}", key, exception);
        }
    }

    protected abstract void validPicture(Object inputSource);
    protected abstract String getOriginFilename(Object inputSource);
    protected abstract void processFile(Object inputSource, File file) throws Exception;

    public void deleteTempFile(File file) {
        if (file != null && file.exists() && !file.delete()) log.warn("临时文件清理失败: {}", file);
    }
}













