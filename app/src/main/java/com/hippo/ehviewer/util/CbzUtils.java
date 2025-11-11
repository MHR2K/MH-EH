package com.hippo.ehviewer.util;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.unifile.UniFile;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class CbzUtils {

    private CbzUtils() {}

    private static final Pattern IMAGE_NAME_PATTERN =
            Pattern.compile("^\\d{8}\\.(jpg|jpeg|png|gif|webp)$", Pattern.CASE_INSENSITIVE);

    public static boolean isCbzMode(UniFile dir) {
        if (dir == null || !dir.isDirectory()) return false;
        UniFile[] files = dir.listFiles();
        if (files == null) return false;
        int cbzCount = 0;
        int imageCount = 0;
        for (UniFile f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name == null) continue;
            String lower = name.toLowerCase();
            if (lower.endsWith(".cbz")) cbzCount++;
            else if (IMAGE_NAME_PATTERN.matcher(name).matches()) imageCount++;
        }
        return cbzCount == 1 && imageCount == 0;
    }

    @Nullable
    public static UniFile findCbzFile(UniFile dir) {
        if (dir == null || !dir.isDirectory()) return null;
        UniFile[] files = dir.listFiles();
        if (files == null) return null;
        UniFile found = null;
        for (UniFile f : files) {
            if (f.isFile()) {
                String name = f.getName();
                if (name != null && name.toLowerCase().endsWith(".cbz")) {
                    if (found != null) return null; // 多于1个，返回null
                    found = f;
                }
            }
        }
        return found;
    }

    public static boolean createCbzWithComicInfo(UniFile dir, GalleryInfo gi, int pages,
                                                 @Nullable String[] simpleTags, boolean removeImagesAfter) {
        if (dir == null || !dir.isDirectory()) return false;

        List<UniFile> images = new ArrayList<>();
        UniFile[] files = dir.listFiles();
        if (files != null) {
            for (UniFile f : files) {
                String name = f.getName();
                if (f.isFile() && name != null && IMAGE_NAME_PATTERN.matcher(name).matches()) {
                    images.add(f);
                }
            }
        }
        if (images.isEmpty()) return false;
        Collections.sort(images, (a,b)->a.getName().compareToIgnoreCase(b.getName()));

        // 目标文件名：优先使用 gid 命名，其次回退为文件夹名
        String finalName;
        if (gi != null && gi.gid > 0) {
            finalName = gi.gid + ".cbz";
        } else {
            finalName = dir.getName() + ".cbz";
        }
        // 如果已有旧的 cbz，先删除，避免 rename 失败（也可考虑备份，这里简单删除）
        UniFile old = dir.findFile(finalName);
        if (old != null) {
            old.delete();
        }

        // 临时文件，确保打包完整后再原子替换
        String tmpName = finalName + ".tmp";
        UniFile tmpFile = dir.createFile(tmpName);
        if (tmpFile == null) return false;

        boolean success = false;
        byte[] buffer = new byte[8192];
        try (OutputStream os = tmpFile.openOutputStream(); ZipOutputStream zos = new ZipOutputStream(os)) {
            // 写入图片
            for (UniFile img : images) {
                String name = img.getName();
                if (name == null) {
                    continue; // 跳过异常文件名
                }
                ZipEntry entry = new ZipEntry(name);
                zos.putNextEntry(entry);
                try (InputStream is = img.openInputStream()) {
                    int read;
                    while ((read = is.read(buffer)) >= 0) {
                        zos.write(buffer, 0, read);
                    }
                } catch (IOException ioe) {
                    Log.e("CbzUtils", "Image write failed: " + name, ioe);
                    return false; // 直接失败，下面 finally 会删临时文件
                } finally {
                    zos.closeEntry();
                }
            }
            // 写入 ComicInfo.xml
            byte[] xmlBytes = buildComicInfoXml(gi, pages, simpleTags);
            ZipEntry infoEntry = new ZipEntry("ComicInfo.xml");
            zos.putNextEntry(infoEntry);
            zos.write(xmlBytes);
            zos.closeEntry();

            success = true; // 若无异常，到此视为成功
        } catch (Exception e) {
            Log.e("CbzUtils", "Create CBZ failed", e);
            success = false;
        }

        if (!success) {
            // 失败清理临时文件
            tmpFile.delete();
            return false;
        }

        // 原子替换：重命名临时文件为最终文件名
        boolean renamed = tmpFile.renameTo(finalName);
        if (!renamed) {
            // 重命名失败则删除临时文件并返回失败
            tmpFile.delete();
            return false;
        }

        // 清理目录内其它旧的 .cbz（只保留目标命名）
        UniFile[] afterFiles = dir.listFiles();
        if (afterFiles != null) {
            for (UniFile f : afterFiles) {
                if (f.isFile()) {
                    String n = f.getName();
                    if (n != null) {
                        String lower = n.toLowerCase();
                        if (lower.endsWith(".cbz") && !n.equals(finalName)) {
                            try { f.delete(); } catch (Throwable ignore) {}
                        }
                    }
                }
            }
        }

        // 成功后删除图片（仅在 removeImagesAfter == true）
        if (removeImagesAfter) {
            for (UniFile img : images) {
                try { img.delete(); } catch (Throwable ignore) { }
            }
        }
        return true;
    }

    public static boolean extractCbz(UniFile dir, boolean deleteCbzAfter) {
        if (dir == null || !dir.isDirectory()) return false;
        UniFile cbz = findCbzFile(dir);
        if (cbz == null) return false;

        ZipInputStream zis = null;
        try {
            InputStream is = cbz.openInputStream();
            zis = new ZipInputStream(is);
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && IMAGE_NAME_PATTERN.matcher(name).matches()) {
                    UniFile out = dir.createFile(name);
                    if (out != null) {
                        OutputStream os = null;
                        try {
                            os = out.openOutputStream();
                            int read;
                            while ((read = zis.read(buffer)) >= 0) {
                                os.write(buffer, 0, read);
                            }
                        } finally {
                            IOUtils.closeQuietly(os);
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            return false;
        } finally {
            IOUtils.closeQuietly(zis);
        }

        if (deleteCbzAfter) cbz.delete();
        return true;
    }

    public static byte[] buildComicInfoXml(GalleryInfo gi, int pages, @Nullable String[] simpleTags) {
        String title = gi != null && gi.title != null ? gi.title : "";
        String uploader = gi != null && gi.uploader != null ? gi.uploader : "";
        String lang = gi != null && gi.simpleLanguage != null ? gi.simpleLanguage : "";
        String web = (gi != null ? ("https://exhentai.org/g/" + gi.gid + "/" + gi.token + "/") : "");
        String summary = "";
        if (simpleTags != null && simpleTags.length > 0) {
            int limit = Math.min(simpleTags.length, 5);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                sb.append(simpleTags[i]);
                if (i < limit - 1) sb.append(", ");
            }
            summary = sb.toString();
        }
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<ComicInfo>\n" +
                "  <Title>" + escapeXml(title) + "</Title>\n" +
                "  <Series>" + escapeXml(uploader) + "</Series>\n" +
                "  <Number></Number>\n" +
                "  <Writer></Writer>\n" +
                "  <LanguageISO>" + escapeXml(lang) + "</LanguageISO>\n" +
                "  <Pages>" + pages + "</Pages>\n" +
                "  <Web>" + escapeXml(web) + "</Web>\n" +
                "  <Summary>" + escapeXml(summary) + "</Summary>\n" +
                "</ComicInfo>";
        return xml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String escapeXml(String s) {
        return s.replace("&","&amp;")
                .replace("<","&lt;")
                .replace(">","&gt;")
                .replace("\"","&quot;")
                .replace("'","&apos;");
    }
}
