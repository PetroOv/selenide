package com.codeborne.selenide.impl;

import com.codeborne.selenide.Driver;
import com.codeborne.selenide.Stopwatch;
import com.codeborne.selenide.ex.FileNotDownloadedError;
import com.codeborne.selenide.files.DownloadAction;
import com.codeborne.selenide.files.DownloadedFile;
import com.codeborne.selenide.files.FileFilter;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.HasDevTools;
import org.openqa.selenium.devtools.v120.browser.Browser;
import org.openqa.selenium.devtools.v120.page.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.File;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.codeborne.selenide.impl.FileHelper.moveFile;
import static java.util.Collections.emptyMap;
import static org.openqa.selenium.devtools.v120.browser.model.DownloadProgress.State.CANCELED;
import static org.openqa.selenium.devtools.v120.browser.model.DownloadProgress.State.COMPLETED;

@ParametersAreNonnullByDefault
public class DownloadFileToFolderCdp {
  private static final Logger log = LoggerFactory.getLogger(DownloadFileToFolderCdp.class);
  private final Downloader downloader;

  DownloadFileToFolderCdp(Downloader downloader) {
    this.downloader = downloader;
  }

  public DownloadFileToFolderCdp() {
    this(new Downloader());
  }

  @CheckReturnValue
  @Nonnull
  public File download(WebElementSource anyClickableElement,
                       WebElement clickable, long timeout,
                       FileFilter fileFilter,
                       DownloadAction action) {

    Driver driver = anyClickableElement.driver();
    DevTools devTools = initDevTools(driver);

    AtomicBoolean downloadComplete = new AtomicBoolean(false);
    AtomicReference<String> fileName = new AtomicReference<>();

    // Init download behaviour and listeners
    prepareDownloadWithCdp(driver, devTools, fileName, downloadComplete);

    // Perform action an element that begins download process
    action.perform(anyClickableElement.driver(), clickable);

    // Wait until download
    File file = waitUntilDownloadsCompleted(anyClickableElement.driver(), timeout, downloadComplete, fileName);

    //
    if (!fileFilter.match(new DownloadedFile(file, emptyMap()))) {
      String message = String.format("Failed to download file in %d ms.%s;%n actually downloaded: %s",
        timeout, fileFilter.description(), file.getAbsolutePath());
      throw new FileNotDownloadedError(driver, message, timeout);
    }

    // Move file to unique folder
    return archiveFile(anyClickableElement.driver(), file);
  }

  @Nonnull
  protected File archiveFile(Driver driver, File downloadedFile) {
    File uniqueFolder = downloader.prepareTargetFolder(driver.config());
    File archivedFile = new File(uniqueFolder, downloadedFile.getName());
    moveFile(downloadedFile, archivedFile);
    log.debug("Moved the downloaded file {} to {}", downloadedFile, archivedFile);
    return archivedFile;
  }

  private File waitUntilDownloadsCompleted(Driver driver, long timeout,
                                           AtomicBoolean downloadComplete, AtomicReference<String> fileName) {
    long pollingInterval = Math.max(driver.config().pollingInterval(), 100);
    Stopwatch stopwatch = new Stopwatch(timeout);
    do {
      if (downloadComplete.get()) {
        log.debug("File {} download is complete", fileName);
        return new File(driver.browserDownloadsFolder().toString(), fileName.get());
      }
      stopwatch.sleep(pollingInterval);
    } while (!stopwatch.isTimeoutReached());

    String message = "Failed to download file in %d ms".formatted(timeout);
    throw new FileNotDownloadedError(driver, message, timeout);
  }

  private DevTools initDevTools(Driver driver) {
    DevTools devTools;
    if (driver.browser().isChromium()) {
      devTools = ((HasDevTools) driver.getWebDriver()).getDevTools();
      devTools.createSessionIfThereIsNotOne();
      devTools.send(Page.enable());
      return devTools;
    } else {
      throw new IllegalArgumentException("The browser you selected \"%s\" doesn't have Chrome Devtools protocol functionality."
        .formatted(driver.browser().name));
    }
  }

  private void prepareDownloadWithCdp(Driver driver, DevTools devTools,
                                      AtomicReference<String> fileName, AtomicBoolean downloadComplete) {
    devTools.send(Browser.setDownloadBehavior(
      Browser.SetDownloadBehaviorBehavior.ALLOW,
      Optional.empty(),
      Optional.of(driver.browserDownloadsFolder().toString()),
      Optional.of(true)));

    devTools.addListener(Browser.downloadWillBegin(), handler -> {
      fileName.set(handler.getSuggestedFilename());
    });

    devTools.addListener(
      Browser.downloadProgress(),
      e -> {
        if (e.getState() == CANCELED) {
          Number receivedBytes = e.getReceivedBytes();
          if (receivedBytes.longValue() == 0L) {
            throw new FileNotDownloadedError(driver, "Failed to download file. Received 0 bytes.", 0);
          }
          throw new FileNotDownloadedError(driver, "File download is canceled", 0);
        }
        downloadComplete.set(e.getState() == COMPLETED);
        log.debug("Download is in progress");
      });

  }
}