package common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class UpdateOptions {
    public static boolean zipFileCheck = true;
    public static boolean ignoreDuplicates = true;
    public static boolean autoCompileEnable = true;
    public static boolean autoCompileWhenNoJarAsset = true;
    public static int autoCompileBranchNewerMonths = 4;
    public static boolean allowPreReleaseDefault = false;
    public static boolean sslVerify = true;
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
    public static List<String> userAgents = new ArrayList<>();
    public static boolean useUpdateFolder = true;
    public static int maxPerHost = 3;
    public static final ConcurrentHashMap<String, Semaphore> hostSemaphores = new ConcurrentHashMap<>();
    public static boolean rollbackEnabled = false;
    public static int rollbackMaxCopies = 3;
    public static String rollbackPath = null;
    public static final List<String> rollbackFilters = new ArrayList<>();
    public static boolean restartAfterUpdate = false;
    public static int restartDelaySec = 5;
    public static String restartMessage = "Server restarting to apply updates.";


    private UpdateOptions() {
    }
}
