import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 测试备份功能的简单工具类
 * 用于验证备份逻辑是否正确工作
 */
public class TestBackup {
    
    public static void main(String[] args) {
        // 测试备份文件命名格式
        String filename = "backup_20250101_120000.db";
        System.out.println("备份文件名格式测试: " + filename);
        System.out.println("是否符合命名规范: " + filename.matches("backup_\\d{8}_\\d{6}\\.db"));
        
        // 测试文件清理逻辑
        File[] testFiles = {
            new File("backup_20250101_120000.db"), // 最旧
            new File("backup_20250102_120000.db"),
            new File("backup_20250103_120000.db"),
            new File("backup_20250104_120000.db"), // 最新
        };
        
        // 模拟文件修改时间
        long baseTime = System.currentTimeMillis();
        for (int i = 0; i < testFiles.length; i++) {
            testFiles[i].setLastModified(baseTime - (testFiles.length - i) * 24 * 60 * 60 * 1000L);
        }
        
        // 按修改时间排序（最新的在前面）
        Arrays.sort(testFiles, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        
        System.out.println("\n文件排序测试:");
        for (int i = 0; i < testFiles.length; i++) {
            System.out.println((i+1) + ". " + testFiles[i].getName() + " - " + new java.util.Date(testFiles[i].lastModified()));
        }
        
        // 测试保留策略（保留最近2个）
        int retentionDays = 2;
        System.out.println("\n保留策略测试（保留最近" + retentionDays + "个）:");
        for (int i = retentionDays; i < testFiles.length; i++) {
            System.out.println("应删除: " + testFiles[i].getName());
        }
    }
}
