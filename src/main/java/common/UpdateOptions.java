package common;

public class UpdateOptions {
    public static boolean zipFileCheck = true;
    public static boolean ignoreDuplicates = true;
    public static boolean autoCompileEnable = true;
    public static boolean autoCompileWhenNoJarAsset = true;
    public static int autoCompileBranchNewerMonths = 4;
    public static boolean allowPreReleaseDefault = false;
    public static String tempPath = null;
    public static String updatePath = null;
    public static String filePath = null;
    public static int maxParallel = 4;
    public static int connectTimeoutMs = 10000;
    public static int readTimeoutMs = 30000;
    public static int perDownloadTimeoutSec = 0;
    public static volatile boolean debug = false;

    private UpdateOptions() {}
}
