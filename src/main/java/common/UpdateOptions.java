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
    public static int maxRetries = 4;
    public static int backoffBaseMs = 500;
    public static int backoffMaxMs = 5000;
    public static java.util.List<String> userAgents = new java.util.ArrayList<>();
    public static boolean useUpdateFolder = true;

    private UpdateOptions() {}
}
