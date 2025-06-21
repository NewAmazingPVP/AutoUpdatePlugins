package common;

import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class PluginDownloader {

    private final Logger logger;

    public PluginDownloader(Logger logger) {
        this.logger = logger;
    }

    public boolean downloadPlugin(String link, String fileName, String githubToken) throws IOException{
        boolean requiresAuth = link.toLowerCase().contains("actions")
                && link.toLowerCase().contains("github")
                && githubToken != null && !githubToken.isEmpty();

        String tempDownloadPath = "plugins/" + fileName + ".zip";
        String outputFilePath   = getString(fileName);

        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL(link).openConnection();
            connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
            if (requiresAuth) {
                connection.setRequestProperty("Authorization", "Bearer " + githubToken);
            }
        } catch (IOException e) {
            logger.warning("Failed to open connection: " + e.getMessage());
            return false;
        }

        try {
            if (!downloadPluginToFile(tempDownloadPath, connection)) {
                return false;
            }

            File tmp = new File(tempDownloadPath);

            if (isZipFile(tempDownloadPath)) {
                extractFirstJarFromZip(tempDownloadPath, outputFilePath);

                if (new File(outputFilePath).exists()) {
                    tmp.delete();
                } else {
                    tmp.renameTo(new File(outputFilePath));
                }
            } else {
                tmp.renameTo(new File(outputFilePath));
            }
            return true;
        } catch (IOException e) {
            logger.warning("Failed to download or extract plugin: " + e.getMessage());
            return false;
        }
    }




    private String getString(String fileName) {
        String outputFilePath = "plugins/" + fileName + ".jar";
        boolean doesUpdateFolderExist = new File("plugins/update/").exists();
        if (doesUpdateFolderExist) {
            File directoryFile = new File("plugins/");
            File[] files = directoryFile.listFiles();
            if (files != null) {
                boolean containsPlugin = false;
                for (File file : files) {
                    if (!file.isDirectory() && file.getName().toLowerCase().contains(fileName.toLowerCase())) {
                        containsPlugin = true;
                        break;
                    }
                }
                if (containsPlugin) {
                    outputFilePath = "plugins/update/" + fileName + ".jar";
                }
            }
        }
        return outputFilePath;
    }

    private boolean downloadPluginToFile(String outputFilePath, HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(outputFilePath)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void extractFirstJarFromZip(String zipFilePath, String outputFilePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipFilePath)) {
            List<? extends ZipEntry> entries = Collections.list(zipFile.entries());
            Optional<? extends ZipEntry> zipEntry = entries.stream()
                    .filter(entry -> !entry.isDirectory() &&
                            !entry.getName().toLowerCase().contains("javadoc") &&
                            !entry.getName().toLowerCase().contains("sources") &&
                            !entry.getName().toLowerCase().contains("api/") &&
                            entry.getName().endsWith(".jar"))
                    .findFirst();

            if (!zipEntry.isPresent()) {
                return;
            }

            try (InputStream in = zipFile.getInputStream(zipEntry.get());
                 FileOutputStream out = new FileOutputStream(outputFilePath)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    public boolean downloadJenkinsPlugin(String link, String fileName){
        String downloadPath = "plugins/" + fileName + ".zip";
        String outputFilePath = getString(fileName);
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL(link).openConnection();
        } catch (IOException e) {
            logger.info("Failed to download or extract plugin: " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

        try {
            downloadPluginToFile(downloadPath, connection);
            if (isZipFile(downloadPath)) {
                extractFirstJarFromZip(downloadPath, outputFilePath);
                new File(downloadPath).delete();
            } else {
                new File(downloadPath).renameTo(new File(outputFilePath));
            }
            return true;
        } catch (IOException e) {
            logger.info("Failed to download or extract plugin: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }


    public static boolean isZipFile(String filePath) {
        Objects.requireNonNull(filePath, "filePath");
        Path path = Paths.get(filePath);

        try (ZipFile ignored = new ZipFile(path.toFile())) {
            return true;
        } catch (ZipException ex) {
            return false;
        } catch (IOException ex) {
            throw new UncheckedIOException("I/O while probing ZIP", ex);
        }
    }
}
