package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class UpdatePlugins {

	public String extractPluginIdFromLink(String spigotResourceLink) {
		Pattern pattern = Pattern.compile("(\\d+)/");
		Matcher matcher = pattern.matcher(spigotResourceLink);
		if (!matcher.find()) {
			return "";
		}
		return matcher.group(1);
	}

	public String getRepoLocation(String inputUrl) throws MalformedURLException {
		URL url = new URL(inputUrl);
		Pattern pattern = Pattern.compile("^/([^/]+)/([^/]+)");
		Matcher matcher = pattern.matcher(url.getPath());

		if (matcher.find()) {
			System.out.println("Repository path not found.");
			return "";
		}
		return matcher.group(0);
	}

	public void updatePlugin(String link, String fileName, String githubToken) throws IOException {
		boolean isGithubActions =
			link.toLowerCase().contains("actions") && link.toLowerCase().contains("github") && githubToken != null && !githubToken.isEmpty();
		String downloadPath = "plugins/" + fileName + ".zip";
		String outputFilePath = "plugins/" + fileName + ".jar";
		if (!isGithubActions) {
			URL url = new URL(link);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
			downloadPluginToFile(outputFilePath, connection);
			return;
		}
		HttpURLConnection connection;
		try {
			URL url = new URL(link);
			connection = (HttpURLConnection) url.openConnection();
		} catch (IOException e) {
			System.out.println("Failed to download or extract plugin: " + e.getMessage());
			e.printStackTrace();
			return;
		}

		connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
		connection.setRequestProperty("Authorization", "Bearer " + githubToken);

		try {
			downloadPluginToFile(downloadPath, connection);
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

	private boolean isZipFile(String filePath) throws IOException {
		try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(filePath))) {
			return zipInputStream.getNextEntry() != null;
		} catch (IOException e) {
			return false;
		}
	}

	private void extractFirstJarFromZip(String zipFilePath, String outputFilePath) throws IOException {
		try (ZipFile zipFile = new ZipFile(zipFilePath)) {
			List<? extends ZipEntry> entries = Collections.list(zipFile.entries());
			Optional<? extends ZipEntry> zipEntry = entries.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".jar")).findFirst();
			if (!zipEntry.isPresent()) {
				return;
			}
			try (InputStream in = zipFile.getInputStream(zipEntry.get()); FileOutputStream out = new FileOutputStream(outputFilePath)) {
				byte[] buffer = new byte[1024];
				int bytesRead;
				while ((bytesRead = in.read(buffer)) != -1) {
					out.write(buffer, 0, bytesRead);
				}
			}
			//maybe try multiple?
		}
	}

	public void readList(File myFile, String platform, String key) {
		CompletableFuture.runAsync(() -> {
			if (myFile.length() == 0) {
				System.out.println("File is empty. Please put FileSaveName: [link to plugin]");
				return;
			}
			Yaml yaml = new Yaml();
			try (FileReader reader = new FileReader(myFile)) {
				Map<String, String> links = yaml.load(reader);
				if (links == null) {
					System.out.println("No data in file. Aborting readList operation.");
					return;
				}
				for (Map.Entry<String, String> entry : links.entrySet()) {
					handleUpdateEntry(platform, key, entry);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		});
	}

	private void handleUpdateEntry(String platform, String key, Map.Entry<String, String> entry) throws IOException {
		try {
			System.out.println(entry.getKey() + " ---- " + entry.getValue());
			String value = entry.getValue();

			boolean blobBuildPhrase = value.contains("blob.build");
			boolean busyBiscuitPhrase = value.contains("thebusybiscuit.github.io/builds");

			boolean hasSpigotPhrase = value.contains("spigotmc.org");
			boolean hasGithubPhrase = value.contains("github.com");
			boolean hasJenkinsPhrase = value.contains("https://ci.");
			boolean hasBukkitPhrase = value.contains("https://dev.bukkit.org/");
			boolean hasModrinthPhrase = value.contains("modrinth.com");
			boolean hasHangarPhrase = value.contains("https://hangar.papermc.io/");

			boolean hasAnyValidPhrase =
				hasSpigotPhrase || hasGithubPhrase || hasJenkinsPhrase || hasBukkitPhrase || hasModrinthPhrase || hasHangarPhrase ||
					blobBuildPhrase || busyBiscuitPhrase;
			if (!value.endsWith("/") && hasAnyValidPhrase && !value.endsWith("]")) {
				value = entry.getValue() + "/";
			}

			if (blobBuildPhrase) {
				handleBlobBuild(value, key, entry);
			} else if (busyBiscuitPhrase) {
				handleBusyBiscuitdownload(value, key, entry);
			} else if (hasSpigotPhrase) {
				handleSpigotDownload(key, entry, value);
			} else if (hasGithubPhrase) {
				handleGitHubDownload(key, entry, value);
			} else if (hasJenkinsPhrase) {
				handleJenkinsDownload(key, entry, value);
			} else if (hasBukkitPhrase) {
				updatePlugin(value + "files/latest", entry.getKey(), key);
			} else if (hasModrinthPhrase) {
				handleModrinthDownload(platform, key, entry, value);
			} else if (hasHangarPhrase) {
				handleHangarDownload(platform, key, entry, value);
			} else {
				updatePlugin(value, entry.getKey(), key);
			}
		} catch (NullPointerException ignored) {
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

			if (!response.get("success").asBoolean()) {
				System.out.println("Failed to fetch from blob.build: " + response.get("error").asText());
				return;
			}
			JsonNode data = response.get("data");
			String downloadUrl = data.get("fileDownloadUrl").asText();
			updatePlugin(downloadUrl, entry.getKey(), key);
		} catch (Exception e) {
			System.out.println("Failed to download plugin from blob.build: " + e.getMessage());
		}
	}

	private void handleBusyBiscuitdownload(String value, String key, Map.Entry<String, String> entry) {
		try {
			Pattern pattern = Pattern.compile("builds/([^/]+)/([^/]+)");
			Matcher matcher = pattern.matcher(value);

			if (!matcher.find()) {
				System.out.println("Invalid TheBusyBiscuit builds URL format");
				return;
			}

			String owner = matcher.group(1);
			String repo = matcher.group(2);

			String apiUrl = String.format("https://thebusybiscuit.github.io/builds/%s/%s/master/builds.json", owner, repo);

			HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
			connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");

			JsonNode buildsData = new ObjectMapper().readTree(connection.getInputStream());

			String lastBuild = buildsData.get("last_successful").asText();

			String downloadUrl = String.format("https://thebusybiscuit.github.io/builds/%s/%s/master/download/%s/%s-%s.jar", owner, repo, lastBuild, repo,
				lastBuild);

			updatePlugin(downloadUrl, entry.getKey(), key);
		} catch (Exception e) {
			System.out.println("Failed to download plugin from TheBusyBiscuit builds: " + e.getMessage());
		}
	}

	private void handleHangarDownload(String platform, String key, Map.Entry<String, String> entry, String value) {
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
			String downloadUrl =
				"https://hangar.papermc.io/api/v1/projects/" + projectName + "/versions/" + latestVersion + "/" + platform.toUpperCase() + "/download";
			updatePlugin(downloadUrl, entry.getKey(), key);
		} catch (IOException e) {
			System.out.println("Failed to download plugin from hangar, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
		}
	}

	private void handleModrinthDownload(String platform, String key, Map.Entry<String, String> entry, String value) {
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
				if (!version.get("loaders").toString().toLowerCase().contains(platform)) {
					continue;
				}
				String downloadUrl = version.get("files").get(0).get("url").asText();
				updatePlugin(downloadUrl, entry.getKey(), key);
				break;
			}
		} catch (IOException e) {
			System.out.println("Failed to download plugin from modrinth, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
		}
	}

	private void handleJenkinsDownload(String key, Map.Entry<String, String> entry, String value) {
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
			if (!jenkinsLink.endsWith("/")) {
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
			System.out.println(
				"Failed to download plugin from jenkins, " + value + " , are you sure link is correct and in right " + "format?" + e.getMessage());
		}
	}

	private void handleGitHubDownload(String key, Map.Entry<String, String> entry, String value) {
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

				JsonNode node = new ObjectMapper().readTree(new URL(apiUrl));

				String downloadUrl = null;
				int times = 0;

				for (JsonNode artifact : node.get("artifacts")) {
					if (artifact.has("name")) {
						times++;
					}
					if (times == artifactNum) {
						downloadUrl = artifact.get("archive_download_url").asText();
						break;
					}
				}

				if (downloadUrl != null) {
					updatePlugin(downloadUrl, entry.getKey(), key);
				} else {
					System.out.println("Failed to find the specified artifact number in the workflow artifacts.");
				}
				return;
			}
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
			JsonNode node = new ObjectMapper().readTree(new URL(apiUrl));

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

		} catch (IOException | NumberFormatException e) {
			System.out.println(
				"Failed to download plugin from github, " + value + " , are you sure the link is correct and in the right format? " + e.getMessage());
		}
	}

	private void handleSpigotDownload(String key, Map.Entry<String, String> entry, String value) {
		try {
			String pluginId = extractPluginIdFromLink(value);
			String downloadUrl = "https://api.spiget.org/v2/resources/" + pluginId + "/download";
			updatePlugin(downloadUrl, entry.getKey(), key);
		} catch (Exception e) {
			System.out.println("Failed to download plugin from spigot, " + value + " , are you sure link is correct and in right format?" + e.getMessage());
		}
	}

	private void downloadPluginToFile(String outputFilePath, HttpURLConnection connection) throws IOException {
		try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(outputFilePath)) {
			byte[] buffer = new byte[1024];
			int bytesRead;
			while ((bytesRead = in.read(buffer)) != -1) {
				out.write(buffer, 0, bytesRead);
			}
		}
	}
}
