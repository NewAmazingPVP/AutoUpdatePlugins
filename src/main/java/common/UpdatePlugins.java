package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class UpdatePlugins {

    public String extractPluginIdFromLink(String spigotResourceLink) {
        Pattern pattern = Pattern.compile("([0-9]+)/");
        Matcher matcher = pattern.matcher(spigotResourceLink);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            return "";
        }
    }

    public String getRepoLocation(String inputUrl) throws MalformedURLException {
        URL url = new URL(inputUrl);
        Pattern pattern = Pattern.compile("^/([^/]+)/([^/]+)");
        Matcher matcher = pattern.matcher(url.getPath());

        if (matcher.find()) {
            return matcher.group(0);
        } else {
            System.out.println("Repository path not found.");
            return "";
        }
    }

    public void extractPluginYml(String jarFilePath, String outputDir) throws IOException {
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            JarEntry entry = jarFile.getJarEntry("plugin.yml");
            if (entry != null) {
                try (InputStream inputStream = jarFile.getInputStream(entry);
                     FileOutputStream outputStream = new FileOutputStream(outputDir + "/plugin.yml")) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
            }
        }
    }

    public void modifyPluginYml(String pluginYmlPath) throws IOException {
        String newLine = "\nfolia-supported: true\n";
        String content = new String(Files.readAllBytes(Paths.get(pluginYmlPath)));

        if (!content.contains(newLine.trim())) {
            Files.write(Paths.get(pluginYmlPath), newLine.getBytes(), StandardOpenOption.APPEND);
        }
    }


    public void repackageJar(String originalJarPath, String modifiedJarPath, String pluginYmlPath) throws IOException {
        try (JarFile jarFile = new JarFile(originalJarPath);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(modifiedJarPath))) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                InputStream inputStream;
                if (entry.getName().equals("plugin.yml")) {
                    inputStream = new FileInputStream(pluginYmlPath);
                } else {
                    inputStream = jarFile.getInputStream(entry);
                }
                jos.putNextEntry(new JarEntry(entry.getName()));
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    jos.write(buffer, 0, bytesRead);
                }
                inputStream.close();
                jos.closeEntry();
            }
        }
    }

    public void updatePlugin(String link, String fileName, String key) throws IOException {
        boolean isGithubActions = link.toLowerCase().contains("actions") && link.toLowerCase().contains("github") && !key.isEmpty();
        String downloadPath = "plugins/" + fileName + ".zip";
        String outputFilePath = "plugins/init" + fileName + ".jar";
        String tempDir = "plugins/AutoUpdatePlugins";

        if (!isGithubActions) {
            URL url = new URL(link);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputFilePath)) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        } else {
            String githubToken = key;
            try {
                URL url = new URL(link);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
                if (githubToken != null && !githubToken.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + githubToken);
                }

                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(downloadPath)) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                if (isZipFile(downloadPath) && link.toLowerCase().contains("actions") && link.toLowerCase().contains("github")) {
                    extractFirstJarFromZip(downloadPath, outputFilePath);
                    new File(downloadPath).delete();
                } else {
                    new File(downloadPath).renameTo(new File(outputFilePath));
                }

            } catch (IOException e) {
                System.out.println("Failed to download or extract plugin: " + e.getMessage());
                e.printStackTrace();
            }
        }

        extractPluginYml(outputFilePath, tempDir);
        modifyPluginYml(tempDir + "/plugin.yml");
        repackageJar(outputFilePath, "plugins/" + fileName + ".jar", tempDir + "/plugin.yml");

        new File(outputFilePath).delete();
        new File(tempDir + "/plugin.yml").delete();
    }


    private boolean isZipFile(String filePath) throws IOException {
        try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(filePath))) {
            return zipInputStream.getNextEntry() != null;
        } catch (IOException e) {
            return false;
        }
    }

    private void extractFirstJarFromZip(String zipFilePath, String outputFilePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(zipFilePath)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".jar")) {
                    try (InputStream in = zipFile.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(outputFilePath)) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    //maybe try multiple?
                    break;
                }
            }
        }
    }
    private void handleBlobBuild(String value, String key, Map.Entry<String, String> entry) {
        try {
            String[] urlParts = value.split("/");
            String projectName = urlParts[urlParts.length - 2];
            String releaseChannel = urlParts[urlParts.length - 1];

            String apiUrl = String.format("https://blob.build/api/builds/%s/%s/latest", projectName, releaseChannel);

            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode response = objectMapper.readTree(connection.getInputStream());

            if (response.get("success").asBoolean()) {
                JsonNode data = response.get("data");
                String downloadUrl = data.get("fileDownloadUrl").asText();
                updatePlugin(downloadUrl, entry.getKey(), key);
            } else {
                System.out.println("Failed to fetch from blob.build: " + response.get("error").asText());
            }
        } catch (Exception e) {
            System.out.println("Failed to download plugin from blob.build: " + e.getMessage());
        }
    }

    private void handleBusyBiscuit(String value, String key, Map.Entry<String, String> entry) {
        try {
            Pattern pattern = Pattern.compile("builds/([^/]+)/([^/]+)");
            Matcher matcher = pattern.matcher(value);

            if (matcher.find()) {
                String owner = matcher.group(1);
                String repo = matcher.group(2);

                String apiUrl = String.format("https://thebusybiscuit.github.io/builds/%s/%s/master/builds.json",
                        owner, repo);

                URL url = new URL(apiUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

                ObjectMapper objectMapper = new ObjectMapper();
                JsonNode buildsData = objectMapper.readTree(connection.getInputStream());

                String lastBuild = buildsData.get("last_successful").asText();

                String downloadUrl = String.format("https://thebusybiscuit.github.io/builds/%s/%s/master/download/%s/%s-%s.jar",
                        owner, repo, lastBuild, repo, lastBuild);

                updatePlugin(downloadUrl, entry.getKey(), key);
            } else {
                System.out.println("Invalid TheBusyBiscuit builds URL format");
            }
        } catch (Exception e) {
            System.out.println("Failed to download plugin from TheBusyBiscuit builds: " + e.getMessage());
        }
    }

    public void readList(File myFile, String platform, String key) throws IOException {
        CompletableFuture.runAsync(() -> {
            if (myFile.length() == 0) {
                System.out.println("File is empty. Please put FileSaveName: [link to plugin]");
            } else {
                Yaml yaml = new Yaml();
                try (FileReader reader = new FileReader(myFile)) {
                    Map<String, String> links = yaml.load(reader);
                    if (links == null) {
                        System.out.println("No data in file. Aborting readList operation.");
                    } else {
                        for (Map.Entry<String, String> entry : links.entrySet()) {
                            try {
                                System.out.println(entry.getKey() + " ---- " + entry.getValue());
                                String value = entry.getValue();

                                boolean blobBuildPhrase = value.contains("blob.build");
                                boolean busyBiscuitPhrase = value.contains("thebusybiscuit.github.io/builds");

                                boolean containsPhrase = value.contains("spigotmc.org");
                                boolean githubPhrase = value.contains("github.com");
                                boolean jenkinsPhrase = value.contains("https://ci.");
                                boolean bukkitPhrase = value.contains("https://dev.bukkit.org/");
                                boolean modrinthPhrase = value.contains("modrinth.com");
                                boolean hangarPhrase = value.contains("https://hangar.papermc.io/");

                                if (!value.endsWith("/") && (containsPhrase || githubPhrase || jenkinsPhrase ||
                                        bukkitPhrase || modrinthPhrase || hangarPhrase || blobBuildPhrase || busyBiscuitPhrase)) {
                                    if(!value.endsWith("]")) {
                                        value = entry.getValue() + "/";
                                    }
                                }

                                if (blobBuildPhrase) {
                                    handleBlobBuild(value, key, entry);
                                } else if (busyBiscuitPhrase) {
                                    handleBusyBiscuit(value, key, entry);
                                } else if (containsPhrase) {
                                    try {
                                        String pluginId = extractPluginIdFromLink(value);
                                        String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
                                        updatePlugin(downloadUrl, entry.getKey(), key);
                                    } catch (Exception e) {
                                        System.out.println("Failed to download plugin from spigot, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (githubPhrase) {
                                    try {
                                        value = value.replace("/actions/", "/dev/");
                                        if (value.endsWith("/dev/")) {
                                            String repoPath;
                                            int artifactNum = 1;
                                            String multiIdentifier = "[";

                                            if (value.contains(multiIdentifier)) {
                                                int startIndex = value.indexOf(multiIdentifier);
                                                int endIndex = value.indexOf("]", startIndex);
                                                artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
                                                repoPath = getRepoLocation(value.substring(0, value.indexOf(multiIdentifier)));
                                            } else {
                                                repoPath = getRepoLocation(value.substring(0, value.length() - 4));
                                            }

                                            String apiUrl = "https://api.github.com/repos" + repoPath + "/actions/artifacts";
                                            ObjectMapper objectMapper = new ObjectMapper();
                                            JsonNode node = objectMapper.readTree(new URL(apiUrl));

                                            String downloadUrl = null;
                                            int times = 0;

                                            for (JsonNode artifact : node.get("artifacts")) {
                                                if (artifact.has("name")) {
                                                    times++;
                                                    if (times == artifactNum) {
                                                        downloadUrl = artifact.get("archive_download_url").asText();
                                                        break;
                                                    }
                                                }
                                            }

                                            if (downloadUrl != null) {
                                                updatePlugin(downloadUrl, entry.getKey(), key);
                                            } else {
                                                System.out.println("Failed to find the specified artifact number in the workflow artifacts.");
                                            }
                                        } else {
                                            String repoPath;
                                            int artifactNum = 1;
                                            String multiIdentifier = "[";

                                            if (value.contains(multiIdentifier)) {
                                                int startIndex = value.indexOf(multiIdentifier);
                                                int endIndex = value.indexOf("]", startIndex);
                                                artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
                                                repoPath = getRepoLocation(value.substring(0, value.indexOf(multiIdentifier)));
                                            } else {
                                                repoPath = getRepoLocation(value);
                                            }

                                            String apiUrl = "https://api.github.com/repos" + repoPath + "/releases/latest";
                                            ObjectMapper objectMapper = new ObjectMapper();
                                            JsonNode node = objectMapper.readTree(new URL(apiUrl));

                                            String downloadUrl = null;
                                            int times = 0;

                                            for (JsonNode asset : node.get("assets")) {
                                                if (asset.has("name") && asset.get("name").asText().endsWith(".jar")) {
                                                    times++;
                                                    if (times == artifactNum) {
                                                        downloadUrl = asset.get("browser_download_url").asText();
                                                        break;
                                                    }
                                                }
                                            }

                                            if (downloadUrl != null) {
                                                updatePlugin(downloadUrl, entry.getKey(), key);
                                            } else {
                                                System.out.println("Failed to find the specified artifact number in the release assets.");
                                            }
                                        }
                                    } catch (IOException | NumberFormatException e) {
                                        System.out.println("Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
                                    }
                                } else if (jenkinsPhrase) {
                                    try {
                                        String jenkinsLink;
                                        int artifactNum = 1;
                                        String multiIdentifier = "[";

                                        if (value.contains(multiIdentifier)) {
                                            int startIndex = value.indexOf(multiIdentifier);
                                            int endIndex = value.indexOf("]", startIndex);
                                            artifactNum = Integer.parseInt(value.substring(startIndex + 1, endIndex));
                                            jenkinsLink = value.substring(0, value.indexOf(multiIdentifier));
                                        } else {
                                            jenkinsLink = value;
                                        }
                                        if(!jenkinsLink.endsWith("/")){
                                            jenkinsLink += "/";
                                        }
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        JsonNode node = objectMapper.readTree(new URL(jenkinsLink + "lastSuccessfulBuild/api/json"));
                                        ArrayNode artifacts = (ArrayNode) node.get("artifacts");
                                        JsonNode selectedArtifact = null;
                                        int times = 0;
                                        for (JsonNode artifact : artifacts) {
                                            times++;
                                            if (times == artifactNum) {
                                                selectedArtifact = artifact;
                                                break;
                                            }
                                        }

                                        if (selectedArtifact == null && !artifacts.isEmpty()) {
                                            selectedArtifact = artifacts.get(0);
                                        }

                                        String artifactName = selectedArtifact.get("relativePath").asText();
                                        String artifactUrl = jenkinsLink + "lastSuccessfulBuild/artifact/" + artifactName;

                                        updatePlugin(artifactUrl, entry.getKey(), key);
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from jenkins, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (bukkitPhrase) {
                                    updatePlugin(value + "files/latest", entry.getKey(), key);
                                } else if (modrinthPhrase) {
                                    try {
                                        String[] parts = value.split("/");
                                        String projectName = parts[parts.length - 1];
                                        String apiUrl = "https://api.modrinth.com/v2/search?query=" + projectName;
                                        ObjectMapper objectMapper = new ObjectMapper();
                                        JsonNode rootNode = objectMapper.readTree(new URL(apiUrl));
                                        ArrayNode hits = (ArrayNode) rootNode.get("hits");
                                        JsonNode firstHit = hits.get(0);
                                        String projectId = firstHit.get("project_id").asText();
                                        String versionUrl = "https://api.modrinth.com/v2/project/" + projectId + "/version";
                                        ArrayNode node = (ArrayNode) objectMapper.readTree(new URL(versionUrl));
                                        for (JsonNode version : node) {
                                            if (version.get("loaders").toString().toLowerCase().contains(platform)) {
                                                String downloadUrl = version.get("files").get(0).get("url").asText();
                                                updatePlugin(downloadUrl, entry.getKey(), key);
                                                break;
                                            }
                                        }
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from modrinth, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else if (hangarPhrase){
                                    try {
                                        String[] parts = value.split("/");
                                        String projectName = parts[parts.length - 1];
                                        String apiUrl = "https://hangar.papermc.io/api/v1/projects/" + projectName + "/latestrelease";
                                        URL url = new URL(apiUrl);
                                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                                        connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

                                        StringBuilder response = new StringBuilder();
                                        try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                                            String inputLine;
                                            while ((inputLine = in.readLine()) != null) {
                                                response.append(inputLine);
                                            }
                                        }
                                        String latestVersion = response.toString().trim();
                                        String downloadUrl = "https://hangar.papermc.io/api/v1/projects/" + projectName + "/versions/" + latestVersion + "/" + platform.toUpperCase() + "/download";
                                        updatePlugin(downloadUrl, entry.getKey(), key);
                                    } catch (IOException e) {
                                        System.out.println("Failed to download plugin from hangar, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
                                    }
                                } else {
                                    updatePlugin(value, entry.getKey(), key);
                                }
                            } catch (NullPointerException ignored) {
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
