package common;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class PluginDownloader {

	public boolean downloadPlugin(String link, String fileName, String githubToken) throws IOException {
		boolean isGithubActions =
			link.toLowerCase().contains("actions") && link.toLowerCase().contains("github") && githubToken != null && !githubToken.isEmpty();
		String downloadPath = "plugins/" + fileName + ".zip";
		String outputFilePath = "plugins/" + fileName + ".jar";
		if (!isGithubActions) {
			HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
			connection.setRequestProperty("User-Agent", "AutoUpdatePlugins");
			return downloadPluginToFile(outputFilePath, connection);

		}
		HttpURLConnection connection;
		try {
			connection = (HttpURLConnection) new URL(link).openConnection();
		} catch (IOException e) {
			System.out.println("Failed to download or extract plugin: " + e.getMessage());
			e.printStackTrace();
			return false;
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
			return false;
		}
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

	private boolean isZipFile(String filePath) throws IOException {
		try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(filePath))) {
			return zipInputStream.getNextEntry() != null;
		} catch (IOException e) {
			return false;
		}
	}
}
