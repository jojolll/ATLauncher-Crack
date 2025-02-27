/*
 * ATLauncher - https://github.com/ATLauncher/ATLauncher
 * Copyright (C) 2013-2021 ATLauncher
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.atlauncher;

import java.awt.Dialog.ModalityType;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;

import com.atlauncher.builders.HTMLBuilder;
import com.atlauncher.constants.Constants;
import com.atlauncher.data.DownloadableFile;
import com.atlauncher.data.LauncherVersion;
import com.atlauncher.gui.dialogs.ProgressDialog;
import com.atlauncher.gui.tabs.FeaturedPacksTab;
import com.atlauncher.gui.tabs.InstancesTab;
import com.atlauncher.gui.tabs.NewsTab;
import com.atlauncher.gui.tabs.PacksBrowserTab;
import com.atlauncher.gui.tabs.ServersTab;
import com.atlauncher.managers.AccountManager;
import com.atlauncher.managers.CheckingServersManager;
import com.atlauncher.managers.ConfigManager;
import com.atlauncher.managers.CurseForgeUpdateManager;
import com.atlauncher.managers.DialogManager;
import com.atlauncher.managers.InstanceManager;
import com.atlauncher.managers.LogManager;
import com.atlauncher.managers.MinecraftManager;
import com.atlauncher.managers.ModpacksChUpdateManager;
import com.atlauncher.managers.NewsManager;
import com.atlauncher.managers.PackManager;
import com.atlauncher.managers.PerformanceManager;
import com.atlauncher.managers.ServerManager;
import com.atlauncher.managers.TechnicModpackUpdateManager;
import com.atlauncher.network.Analytics;
import com.atlauncher.network.DownloadPool;
import com.atlauncher.utils.Java;
import com.atlauncher.utils.OS;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import org.mini2Dx.gettext.GetText;

import net.arikia.dev.drpc.DiscordRPC;
import okhttp3.OkHttpClient;

public class Launcher {
    // Holding update data
    private LauncherVersion latestLauncherVersion; // Latest Launcher version
    private List<DownloadableFile> launcherFiles; // Files the Launcher needs to download

    // UI things
    private JFrame parent; // Parent JFrame of the actual Launcher
    private InstancesTab instancesPanel; // The instances panel
    private ServersTab serversPanel; // The instances panel
    private NewsTab newsPanel; // The news panel
    private FeaturedPacksTab featuredPacksPanel; // The featured packs panel
    private PacksBrowserTab packsBrowserPanel; // The packs browser panel

    // Update thread
    private Thread updateThread;

    // Minecraft tracking variables
    private Process minecraftProcess = null; // The process minecraft is running on
    public boolean minecraftLaunched = false; // If Minecraft has been Launched

    public void loadEverything() {
        PerformanceManager.start();
        if (hasUpdatedFiles()) {
            downloadUpdatedFiles(); // Downloads updated files on the server
        }

//        checkForLauncherUpdate();

        ConfigManager.loadConfig(); // Load the config

        NewsManager.loadNews(); // Load the news

        MinecraftManager.loadMinecraftVersions(); // Load info about the different Minecraft versions

        // Load info about the different java runtimes
        App.TASKPOOL.execute(() -> {
            MinecraftManager.loadJavaRuntimes();
        });

        PackManager.loadPacks(); // Load the Packs available in the Launcher

        PackManager.loadUsers(); // Load the Testers and Allowed Players for the packs

        InstanceManager.loadInstances(); // Load the users installed Instances

        ServerManager.loadServers(); // Load the users installed servers

        AccountManager.loadAccounts(); // Load the saved Accounts

        CheckingServersManager.loadCheckingServers(); // Load the saved servers we're checking with the tool

        PackManager.removeUnusedImages(); // remove unused pack images

        if (OS.isWindows() && !Java.is64Bit() && OS.is64Bit()) {
            LogManager.warn("You're using 32 bit Java on a 64 bit Windows install!");

            int ret = DialogManager.yesNoDialog().setTitle(GetText.tr("Running 32 Bit Java on 64 Bit Windows"))
                    .setContent(new HTMLBuilder().center().text(GetText.tr(
                            "We have detected that you're running 64 bit Windows but not 64 bit Java.<br/><br/>This will cause severe issues playing all packs if not fixed.<br/><br/>Do you want to close the launcher and learn how to fix this issue now?"))
                            .build())
                    .setType(DialogManager.ERROR).show();

            if (ret == 0) {
                OS.openWebBrowser("https://atlauncher.com/help/32bit/");
                System.exit(0);
            }
        }

        if (App.settings.enableServerChecker) {
            CheckingServersManager.startCheckingServers();
        }

        checkForExternalPackUpdates();

        if (!App.settings.firstTimeRun && App.settings.enableLogs && App.settings.enableAnalytics) {
            Analytics.startSession();
        }
        PerformanceManager.end();
    }

    public boolean launcherHasUpdate() {
        try {
            this.latestLauncherVersion = Gsons.DEFAULT
                    .fromJson(new FileReader(FileSystem.JSON.resolve("version.json").toFile()), LauncherVersion.class);
        } catch (JsonSyntaxException | FileNotFoundException | JsonIOException e) {
            LogManager.logStackTrace("Exception when loading latest launcher version!", e);
        }

        return this.latestLauncherVersion != null && Constants.VERSION.needsUpdate(this.latestLauncherVersion);
    }

    /**
     * This checks the servers files.json file and gets the files that the Launcher
     * needs to have
     */
    private List<com.atlauncher.network.Download> getLauncherFiles() {
        if (this.launcherFiles == null) {
            java.lang.reflect.Type type = new TypeToken<List<DownloadableFile>>() {
            }.getType();

            try {
                this.launcherFiles = com.atlauncher.network.Download.build().cached()
                        .setUrl(String.format("%s/launcher/json/files.json", Constants.DOWNLOAD_SERVER)).asType(type);
            } catch (Exception e) {
                LogManager.logStackTrace("Error loading in file hashes!", e);
                return null;
            }
        }

        if (this.launcherFiles == null) {
            return null;
        }

        return this.launcherFiles.stream()
                .filter(file -> !file.isLauncher() && !file.isFiles() && file.isForArchAndOs())
                .map(DownloadableFile::getDownload).collect(Collectors.toList());
    }

    public void downloadUpdatedFiles() {
        ProgressDialog progressDialog = new ProgressDialog(GetText.tr("Downloading Updates"), 1,
                GetText.tr("Downloading Updates"));
        progressDialog.addThread(new Thread(() -> {
            DownloadPool pool = new DownloadPool();
            OkHttpClient httpClient = Network.createProgressClient(progressDialog);
            pool.addAll(
                    getLauncherFiles().stream().map(dl -> dl.withHttpClient(httpClient)).collect(Collectors.toList()));
            DownloadPool smallPool = pool.downsize();

            progressDialog.setTotalBytes(smallPool.totalSize());

            pool.downloadAll();
            progressDialog.doneTask();
            progressDialog.close();
        }));
        progressDialog.start();

        LogManager.info("Finished downloading updated files!");
    }

    public boolean checkForUpdatedFiles() {
        this.launcherFiles = null;

        App.TASKPOOL.execute(() -> {
            checkForExternalPackUpdates();
        });

        return hasUpdatedFiles();
    }

    /**
     * This checks the servers hashes.json file and looks for new/updated files that
     * differ from what the user has
     */
    public boolean hasUpdatedFiles() {
        LogManager.info("Checking for updated files!");
        List<com.atlauncher.network.Download> downloads = getLauncherFiles();

        if (downloads == null) {
            return false;
        }

        return downloads.stream().anyMatch(com.atlauncher.network.Download::needToDownload);
    }

    public void checkForExternalPackUpdates() {
        if (updateThread != null && updateThread.isAlive()) {
            updateThread.interrupt();
        }

        updateThread = new Thread(() -> {
            if (InstanceManager.getInstances().stream().anyMatch(i -> i.isModpacksChPack())) {
                ModpacksChUpdateManager.checkForUpdates();
            }
            if (InstanceManager.getInstances().stream().anyMatch(i -> i.isCurseForgePack())) {
                CurseForgeUpdateManager.checkForUpdates();
            }
            if (InstanceManager.getInstances().stream().anyMatch(i -> i.isTechnicPack())) {
                TechnicModpackUpdateManager.checkForUpdates();
            }
        });
        updateThread.start();
    }

    public void updateData() {
        updateData(false);
    }

    public void updateData(boolean force) {
        if (checkForUpdatedFiles()) {
            reloadLauncherData();
        }

        MinecraftManager.loadMinecraftVersions(force); // Load info about the different Minecraft versions
        MinecraftManager.loadJavaRuntimes(force); // Load info about the different java runtimes
    }

    public void reloadLauncherData() {
        final JDialog dialog = new JDialog(this.parent, ModalityType.DOCUMENT_MODAL);
        dialog.setSize(300, 100);
        dialog.setTitle("Updating Launcher");
        dialog.setLocationRelativeTo(App.launcher.getParent());
        dialog.setLayout(new FlowLayout());
        dialog.setResizable(false);
        dialog.add(new JLabel(GetText.tr("Updating Launcher. Please Wait")));
        App.TASKPOOL.execute(() -> {
            if (hasUpdatedFiles()) {
                downloadUpdatedFiles(); // Downloads updated files on the server
            }
//            checkForLauncherUpdate();
            checkForExternalPackUpdates();

            ConfigManager.loadConfig(); // Load the config
            NewsManager.loadNews(); // Load the news
            reloadNewsPanel(); // Reload news panel
            PackManager.loadPacks(); // Load the Packs available in the Launcher
            reloadFeaturedPacksPanel(); // Reload packs panel
            reloadPacksBrowserPanel();// Reload packs browser panel
            PackManager.loadUsers(); // Load the Testers and Allowed Players for the packs
            InstanceManager.loadInstances(); // Load the users installed Instances
            reloadInstancesPanel(); // Reload instances panel
            reloadServersPanel(); // Reload instances panel
            dialog.setVisible(false); // Remove the dialog
            dialog.dispose(); // Dispose the dialog
        });
        dialog.setVisible(true);
    }

    /*private void checkForLauncherUpdate() {
        PerformanceManager.start();

        LogManager.debug("Checking for launcher update");
        if (launcherHasUpdate()) {
            if (App.noLauncherUpdate) {
                int ret = DialogManager.okDialog().setTitle("Launcher Update Available")
                        .setContent(new HTMLBuilder().center().split(80).text(GetText.tr(
                                "An update to the launcher is available. Please update via your package manager or manually by visiting https://atlauncher.com/downloads to get the latest features and bug fixes."))
                                .build())
                        .addOption(GetText.tr("Visit Downloads Page")).setType(DialogManager.INFO).show();

                if (ret == 1) {
                    OS.openWebBrowser("https://atlauncher.com/downloads");
                }

                return;
            }
        }
        LogManager.debug("Finished checking for launcher update");
        PerformanceManager.end();
    }*/

    /**
     * Sets the main parent JFrame reference for the Launcher
     *
     * @param parent The Launcher main JFrame
     */
    public void setParentFrame(JFrame parent) {
        this.parent = parent;
    }

    public void setMinecraftLaunched(boolean launched) {
        this.minecraftLaunched = launched;
        App.TRAY_MENU.setMinecraftLaunched(launched);
    }

    /**
     * Returns the JFrame reference of the main Launcher
     *
     * @return Main JFrame of the Launcher
     */
    public Window getParent() {
        return this.parent;
    }

    /**
     * Sets the panel used for Instances
     *
     * @param instancesPanel Instances Panel
     */
    public void setInstancesPanel(InstancesTab instancesPanel) {
        this.instancesPanel = instancesPanel;
    }

    /**
     * Reloads the panel used for Instances
     */
    public void reloadInstancesPanel() {
        if (instancesPanel != null) {
            this.instancesPanel.reload(); // Reload the instances panel
        }
    }

    public void setServersPanel(ServersTab serversPanel) {
        this.serversPanel = serversPanel;
    }

    public void reloadServersPanel() {
        if (serversPanel != null) {
            this.serversPanel.reload(); // Reload the servers panel
        }
    }

    /**
     * Sets the panel used for Featured Packs
     *
     * @param featuredPacksPanel Featured Packs Panel
     */
    public void setFeaturedPacksPanel(FeaturedPacksTab featuredPacksPanel) {
        this.featuredPacksPanel = featuredPacksPanel;
    }

    /**
     * Sets the panel used for the Packs Browser
     *
     * @param packsBrowserPanel Packs Browser Panel
     */
    public void setPacksBrowserPanel(PacksBrowserTab packsBrowserPanel) {
        this.packsBrowserPanel = packsBrowserPanel;
    }

    /**
     * Sets the panel used for News
     *
     * @param newsPanel News Panel
     */
    public void setNewsPanel(NewsTab newsPanel) {
        this.newsPanel = newsPanel;
    }

    /**
     * Reloads the panel used for News
     */
    public void reloadNewsPanel() {
        this.newsPanel.reload(); // Reload the news panel
    }

    /**
     * Reloads the panel used for Featured Packs
     */
    public void reloadFeaturedPacksPanel() {
        this.featuredPacksPanel.reload();
    }

    /**
     * Refreshes the panel used for Featured Packs
     */
    public void refreshFeaturedPacksPanel() {
        this.featuredPacksPanel.refresh();
    }

    /**
     * Reloads the panel used for the Packs browser
     */
    public void reloadPacksBrowserPanel() {
        this.packsBrowserPanel.reload(); // Reload the packs browser panel
    }

    /**
     * Refreshes the panel used for  thePacks browser
     */
    public void refreshPacksBrowserPanel() {
        this.packsBrowserPanel.refresh(); // Refresh the packs browser panel
    }

    public void showKillMinecraft(Process minecraft) {
        this.minecraftProcess = minecraft;
        App.console.showKillMinecraft();
    }

    public void hideKillMinecraft() {
        App.console.hideKillMinecraft();
    }

    public void killMinecraft() {
        if (this.minecraftProcess != null) {
            LogManager.error("Killing Minecraft");

            if (App.discordInitialized) {
                DiscordRPC.discordClearPresence();
            }

            this.minecraftProcess.destroy();
            this.minecraftProcess = null;
        } else {
            LogManager.error("Cannot kill Minecraft as there is no instance open!");
        }
    }
}
