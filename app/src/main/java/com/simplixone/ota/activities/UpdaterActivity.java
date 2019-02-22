/*
 * Copyright (C) 2012 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
 * Copyright (C) 2018 Pixel Experience (jhenrique09)
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */
package com.simplixone.ota.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.simplixone.ota.helpers.RebootHelper;
import com.simplixone.ota.misc.Constants;
import com.simplixone.ota.misc.State;
import com.simplixone.ota.misc.UpdateInfo;
import com.simplixone.ota.receiver.DownloadReceiver;
import com.simplixone.ota.utils.MD5;
import com.simplixone.ota.utils.UpdateChecker;
import com.simplixone.ota.utils.UpdateFilter;
import com.simplixone.ota.utils.Utils;

import com.simplixone.ota.R;

import com.simplixone.ota.preferences.UpdatePreference;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class UpdaterActivity extends PreferenceActivity implements
        Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener, UpdatePreference.OnReadyListener,
        UpdatePreference.OnActionListener {
    private static final String UPDATES_CATEGORY = "updates_category";
    private static final String PREF_DOWNLOAD_FOLDER = "pref_download_folder";
    private static String DEVELOPER_URL = "";
    private static String DONATE_URL = "";
    private static String FORUM_URL = "";
    private static String WEBSITE_URL = "";
    private static String NEWS_URL = "";
    private SharedPreferences mPrefs;
    private SwitchPreference mMobileDataWarning;
    private PreferenceScreen preferenceScreen;
    private PreferenceCategory mUpdatesList;
    private Preference mLastCheckPreference;
    private UpdatePreference mCurrentUpdate;

    private File mUpdateFolder;
    private boolean mStartUpdateVisible = false;
    private ProgressDialog mProgressDialog;

    private DownloadManager mDownloadManager;
    private boolean mDownloading = false;
    private long mDownloadId;
    private String mFileName;

    private Handler mUpdateHandler = new Handler();
    private Runnable mUpdateProgress = new Runnable() {
        public void run() {
            if (!mDownloading || mCurrentUpdate == null || mDownloadId < 0) {
                return;
            }

            ProgressBar progressBar = mCurrentUpdate.getProgressBar();
            if (progressBar == null) {
                return;
            }

            Button stopDownloadButton = mCurrentUpdate.getStopDownloadButton();
            if (stopDownloadButton == null) {
                return;
            }

            // Enable updates button
            stopDownloadButton.setEnabled(true);

            DownloadManager.Query q = new DownloadManager.Query();
            q.setFilterById(mDownloadId);

            Cursor cursor = mDownloadManager.query(q);
            int status;

            if (cursor == null || !cursor.moveToFirst()) {
                // DownloadReceiver has likely already removed the download
                // from the DB due to failure or signature mismatch
                status = DownloadManager.STATUS_FAILED;
            } else {
                status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
            }

            switch (status) {
                case DownloadManager.STATUS_PENDING:
                    progressBar.setIndeterminate(true);
                    break;
                case DownloadManager.STATUS_PAUSED:
                case DownloadManager.STATUS_RUNNING:
                    int downloadedBytes = cursor.getInt(
                            cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    int totalBytes = cursor.getInt(
                            cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));

                    if (totalBytes < 0) {
                        progressBar.setIndeterminate(true);
                    } else {
                        progressBar.setIndeterminate(false);
                        progressBar.setMax(totalBytes);
                        progressBar.setProgress(downloadedBytes);
                        int percent = (int) ((downloadedBytes * 100L) / totalBytes);
                        mCurrentUpdate.updateDownloadPercent(percent);
                    }
                    break;
                case DownloadManager.STATUS_FAILED:
                    mCurrentUpdate.setStyle(UpdatePreference.STYLE_NEW);
                    resetDownloadState();
                    break;
                case DownloadManager.STATUS_SUCCESSFUL:
                    mCurrentUpdate.setStyle(UpdatePreference.STYLE_COMPLETING);
                    break;
            }

            if (cursor != null) {
                cursor.close();
            }
            if (status != DownloadManager.STATUS_FAILED
                    && status != DownloadManager.STATUS_SUCCESSFUL) {
                mUpdateHandler.postDelayed(this, 1000);
            }
        }
    };
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (DownloadReceiver.ACTION_DOWNLOAD_STARTED.equals(action)) {
                mDownloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                mUpdateHandler.post(mUpdateProgress);
            } else if (UpdateChecker.ACTION_CHECK_FINISHED.equals(action)) {
                if (mProgressDialog != null) {
                    mProgressDialog.dismiss();
                    mProgressDialog = null;

                    boolean isAvailable = intent.getBooleanExtra(UpdateChecker.EXTRA_UPDATE_AVAILABLE, false);
                    int result = intent.getIntExtra(UpdateChecker.EXTRA_CHECK_RESULT, 0);
                    if (result == 0) {
                        showToast(getString(R.string.update_check_failed), Toast.LENGTH_LONG);
                    } else {
                        showToast(getString(isAvailable ? R.string.update_found_notification : R.string.no_updates_available), Toast.LENGTH_SHORT);
                    }
                }
                updateLayout(false);
            }
        }
    };

    @Override
    public boolean isValidFragment(String fragmentName) {
        return UpdaterActivity.class.getName().equals(fragmentName);
    }

    void updateLayout(Boolean forceShowDownloading) {
        // Read existing Updates
        LinkedList<String> existingFiles = new LinkedList<>();

        mUpdateFolder = Utils.makeUpdateFolder();
        File[] files = mUpdateFolder.listFiles(new UpdateFilter(".zip"));

        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory() && files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    existingFiles.add(file.getName());
                }
            }
        }

        // Clear the notification if one exists
        Utils.cancelNotification(this);

        // Build list of updates
        UpdateInfo savedUpdate = State.loadState(this);
        UpdateInfo update = null;

        if (savedUpdate != null) {
            update = savedUpdate;
            if (existingFiles.contains(savedUpdate.getFileName())) {
                UpdateInfo ui = new UpdateInfo.Builder()
                        .setFileName(update.getFileName())
                        .setFilesize(update.getFileSize())
                        .setBuildDate(update.getDate())
                        .setMD5(update.getMD5())
                        .setDeveloper(update.getDeveloper())
                        .setDeveloperUrl(update.getDeveloperUrl())
                        .setChangelog(update.getChangelog())
                        .setDonateUrl(update.getDonateUrl())
                        .setForumUrl(update.getForumUrl())
                        .setWebsiteUrl(update.getWebsiteUrl())
                        .setNewsUrl(update.getNewsUrl())
                        .setAddons(update.getAddonsInJson())
                        .build();
                update = ui;
            }
        }

        // Update the preference list
        refreshPreferences(update, forceShowDownloading);
    }

    private boolean isDownloadCompleting(String fileName) {
        return new File(mUpdateFolder, fileName + Constants.DOWNLOAD_TMP_EXT).isFile();
    }

    private void updateLastCheckPreference() {
        if (mLastCheckPreference != null) {
            mLastCheckPreference.setTitle(mPrefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0) == 0 ? "" : getLastCheck());
        }
    }

    private void refreshPreferences(UpdateInfo ui, Boolean forceShowDownloading) {
        if (mUpdatesList == null) {
            mCurrentUpdate = null;
            return;
        }

        // Clear the list
        mUpdatesList.removeAll();
        mCurrentUpdate = null;

        mLastCheckPreference = new Preference(this);
        mLastCheckPreference.setLayoutResource(R.layout.preference_last_checked);
        mLastCheckPreference.setSelectable(false);
        mLastCheckPreference.setEnabled(false);
        mUpdatesList.addPreference(mLastCheckPreference);

        updateLastCheckPreference();


        boolean isUpdateAvailable = false;

        if (ui != null) {

            isUpdateAvailable = ui.isNewerThanInstalled();

            if (isUpdateAvailable) {

                // Determine the preference style and create the preference
                boolean isDownloading = forceShowDownloading || ui.getFileName().equals(mFileName);
                int style;

                if (isDownloading) {
                    // In progress download
                    style = UpdatePreference.STYLE_DOWNLOADING;
                } else if (isDownloadCompleting(ui.getFileName())) {
                    style = UpdatePreference.STYLE_COMPLETING;
                    mDownloading = true;
                    mFileName = ui.getFileName();
                } else if (ui.getDownloadUrl() != null) {
                    style = UpdatePreference.STYLE_NEW;
                } else {
                    style = UpdatePreference.STYLE_DOWNLOADED;
                }

                mCurrentUpdate = new UpdatePreference(this, ui, style);
                mCurrentUpdate.setOnActionListener(this);
                mCurrentUpdate.setKey(ui.getFileName());

                // If we have an in progress download, link the preference
                if (isDownloading) {
                    mCurrentUpdate.setOnReadyListener(this);
                    mDownloading = true;
                }

                // Add to the list
                mUpdatesList.addPreference(mCurrentUpdate);
            }
        }

        // If no updates are in the list, show the default message
        if (!isUpdateAvailable) {
            Preference pref = new Preference(this);
            pref.setLayoutResource(R.layout.preference_empty_list);
            pref.setTitle(R.string.no_updates_available_pref_title);
            pref.setSelectable(false);
            pref.setEnabled(false);
            mUpdatesList.addPreference(pref);
        }
    }

    private String getLastCheck() {
        Date lastCheck = new Date(mPrefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0));
        String date = DateFormat.getDateFormat(this).format(lastCheck);
        String time = DateFormat.getTimeFormat(this).format(lastCheck);
        return String.format(getString(R.string.last_updated_at), String.format("%1$s - %2$s", time, date));
    }

    void showToast(String message, int duration) {
        Toast.makeText(this, message, duration).show();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getActionBar().setDisplayHomeAsUpEnabled(true);

        ListView lv = getListView();
        lv.setDivider(new ColorDrawable(Color.TRANSPARENT));
        lv.setDividerHeight(0);

        mDownloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

        addPreferencesFromResource(R.xml.preference_main);

        preferenceScreen = getPreferenceScreen();

        mUpdatesList = (PreferenceCategory) findPreference(UPDATES_CATEGORY);
        mMobileDataWarning = (SwitchPreference) findPreference(Constants.MOBILE_DATA_WARNING_PREF);

        Preference mDownloadFolder = (Preference) findPreference(PREF_DOWNLOAD_FOLDER);
        mDownloadFolder.setSummary(Utils.makeUpdateFolder().getPath());

        // Load the stored preference data
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mMobileDataWarning.setChecked(mPrefs.getBoolean(Constants.MOBILE_DATA_WARNING_PREF, true));
        mMobileDataWarning.setOnPreferenceChangeListener(this);

        // clean temp dir
        Utils.deleteTempFolder();
        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mPrefs.getLong(Constants.LAST_UPDATE_CHECK_PREF, 0) == 0) { //never checked
                    checkForUpdates();
                }
            }
        }, 500);
    }

    void checkForUpdates() {
        if (mProgressDialog != null) {
            return;
        }

        // If there is no internet connection, display a message and return.
        if (!Utils.isOnline(this)) {
            showToast(getString(R.string.data_connection_required), Toast.LENGTH_LONG);
            return;
        }

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setMessage(getString(R.string.checking_for_updates));
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setCanceledOnTouchOutside(false);
        mProgressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                UpdateChecker.cancelAllRequests(UpdaterActivity.this);
                mProgressDialog = null;
            }
        });

        new UpdateChecker(this, null).check();

        mProgressDialog.show();
    }

    private void resetDownloadState() {
        mDownloadId = -1;
        mFileName = null;
        mDownloading = false;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object o) {
        if (preference == mMobileDataWarning) {
            boolean checked = Boolean.valueOf(o.toString());
            mPrefs.edit().putBoolean(Constants.MOBILE_DATA_WARNING_PREF, checked).apply();
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {

        return false;
    }

    @Override
    public void onStart() {
        super.onStart();

        getListView().setNestedScrollingEnabled(false);

        Boolean forceShowDownloading = false;

        // Determine if there are any in-progress downloads
        mDownloadId = mPrefs.getLong(Constants.DOWNLOAD_ID, -1);
        if (mDownloadId >= 0) {
            Cursor c =
                    mDownloadManager.query(new DownloadManager.Query().setFilterById(mDownloadId));
            if (c == null || !c.moveToFirst()) {
                showToast(getString(R.string.download_not_found), Toast.LENGTH_SHORT);
            } else {
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                Uri uri = Uri.parse(c.getString(c.getColumnIndex(DownloadManager.COLUMN_URI)));
                if (status == DownloadManager.STATUS_PENDING
                        || status == DownloadManager.STATUS_RUNNING
                        || status == DownloadManager.STATUS_PAUSED) {
                    mFileName = uri.getLastPathSegment();
                    forceShowDownloading = true;
                }
            }
            if (c != null) {
                c.close();
            }
        }
        if (mDownloadId < 0 || mFileName == null) {
            resetDownloadState();
        }

        updateLayout(forceShowDownloading);

        IntentFilter filter = new IntentFilter(UpdateChecker.ACTION_CHECK_FINISHED);
        filter.addAction(DownloadReceiver.ACTION_DOWNLOAD_STARTED);
        registerReceiver(mReceiver, filter);

        checkForDownloadCompleted(getIntent());
        setIntent(null);

        if (!Utils.isOTAConfigured()) {
            showToast(getResources().getString(R.string.ota_not_supported), Toast.LENGTH_LONG);
            finish();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        mUpdateHandler.removeCallbacks(mUpdateProgress);
        unregisterReceiver(mReceiver);
        if (mProgressDialog != null) {
            mProgressDialog.cancel();
            mProgressDialog = null;
        }
    }

    @Override
    public void onStartDownload(final UpdatePreference pref) {
        mCurrentUpdate = pref;
        // If there is no internet connection, display a message and return.
        if (!Utils.isOnline(this)) {
            showToast(getString(R.string.data_connection_required), Toast.LENGTH_LONG);
            return;
        }

        UpdateInfo ui = pref.getUpdateInfo();

        if (!Utils.isValidURL(ui.getDownloadUrl())) {
            showToast(getString(R.string.notif_download_failure_title), Toast.LENGTH_SHORT);
            return;
        }

        if (mDownloading) {
            showToast(getString(R.string.download_already_running), Toast.LENGTH_SHORT);
            return;
        }

        if (mPrefs.getBoolean(Constants.MOBILE_DATA_WARNING_PREF, true) && Utils.isOnMobileData(this)) {
            View checkboxView = LayoutInflater.from(this).inflate(R.layout.checkbox_view, null);
            final CheckBox checkbox = (CheckBox) checkboxView.findViewById(R.id.checkbox);
            checkbox.setText(R.string.checkbox_mobile_data_warning);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.update_on_mobile_data_title)
                    .setMessage(R.string.update_on_mobile_data_message)
                    .setView(checkboxView)
                    .setPositiveButton(R.string.download_button,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    if (checkbox.isChecked()) {
                                        mPrefs.edit()
                                                .putBoolean(Constants.MOBILE_DATA_WARNING_PREF, false)
                                                .apply();
                                        mMobileDataWarning.setChecked(false);
                                    }
                                    tryToStartDownload();
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();

        } else {
            tryToStartDownload();
        }

    }

    private void tryToStartDownload() {
        if (!isStoragePermissionGranted(Constants.DOWNLOAD_REQUEST_CODE)) {
            showToast(getString(R.string.storage_permission_error), Toast.LENGTH_SHORT);
            return;
        }

        startDownload();
    }


    @Override
    public void onStopCompletingDownload(final UpdatePreference pref) {
        if (!mDownloading || mFileName == null) {
            pref.setStyle(UpdatePreference.STYLE_NEW);
            resetDownloadState();
            return;
        }

        final File tmpZip = new File(mUpdateFolder, mFileName + Constants.DOWNLOAD_TMP_EXT);
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_download_cancel_dialog_title)
                .setMessage(R.string.confirm_download_cancel_dialog_message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (!tmpZip.isFile() || tmpZip.delete()) {
                            // Set the preference back to new style
                            pref.setStyle(UpdatePreference.STYLE_NEW);
                            resetDownloadState();
                            showToast(getString(R.string.download_cancelled), Toast.LENGTH_SHORT);
                        } else {
                            showToast(getString(R.string.unable_to_stop_download), Toast.LENGTH_SHORT);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onStopDownload(final UpdatePreference pref) {
        if (!mDownloading || mFileName == null || mDownloadId < 0) {
            pref.setStyle(UpdatePreference.STYLE_NEW);
            resetDownloadState();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_download_cancel_dialog_title)
                .setMessage(R.string.confirm_download_cancel_dialog_message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Set the preference back to new style
                        pref.setStyle(UpdatePreference.STYLE_NEW);

                        // We are OK to stop download, trigger it
                        mDownloadManager.remove(mDownloadId);
                        mUpdateHandler.removeCallbacks(mUpdateProgress);
                        resetDownloadState();

                        // Clear the stored data from shared preferences
                        mPrefs.edit()
                                .remove(Constants.DOWNLOAD_ID)
                                .apply();

                        showToast(getString(R.string.download_cancelled), Toast.LENGTH_SHORT);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    void checkForDownloadCompleted(Intent intent) {
        if (intent == null) {
            return;
        }

        long downloadId = intent.getLongExtra(Constants.EXTRA_FINISHED_DOWNLOAD_ID, -1);
        if (downloadId < 0) {
            return;
        }

        String fullPathName = intent.getStringExtra(Constants.EXTRA_FINISHED_DOWNLOAD_PATH);
        if (fullPathName == null) {
            return;
        }

        if (mCurrentUpdate != null) {
            mCurrentUpdate.setStyle(UpdatePreference.STYLE_DOWNLOADED);
            onStartUpdate(mCurrentUpdate);
        }

        resetDownloadState();
    }

    @Override
    public void onDeleteUpdate(UpdatePreference pref) {
        final String fileName = pref.getKey();

        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory()) {
            File zipFileToDelete = new File(mUpdateFolder, fileName);

            if (zipFileToDelete.exists()) {
                zipFileToDelete.delete();
            } else {
                return;
            }

            showToast(getString(R.string.delete_update_success_message, fileName), Toast.LENGTH_SHORT);
        } else {
            showToast(getString(mUpdateFolder.exists() ?
                    R.string.delete_updates_failure_message :
                    R.string.delete_updates_noFolder_message), Toast.LENGTH_SHORT);
        }
        // Update the list
        updateLayout(false);
    }

    private void startDownload() {
        if (mCurrentUpdate == null) {
            return;
        }
        UpdateInfo ui = mCurrentUpdate.getUpdateInfo();
        if (ui == null) {
            return;
        }

        mCurrentUpdate.setStyle(UpdatePreference.STYLE_DOWNLOADING);

        mFileName = ui.getFileName();
        mDownloading = true;

        // Start the download
        Intent intent = new Intent(this, DownloadReceiver.class);
        intent.setAction(DownloadReceiver.ACTION_START_DOWNLOAD);
        intent.putExtra(DownloadReceiver.EXTRA_UPDATE_INFO, (Parcelable) ui);
        sendBroadcast(intent);

        mUpdateHandler.post(mUpdateProgress);
    }

    public void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_all_dialog_message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // We are OK to delete, trigger it
                        deleteOldUpdates();
                        updateLayout(false);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean deleteOldUpdates() {
        boolean success;
        //mUpdateFolder: Foldername with fullpath of SDCARD
        if (mUpdateFolder.exists() && mUpdateFolder.isDirectory()) {
            Utils.deleteDir(mUpdateFolder);
            mUpdateFolder.mkdir();
            success = true;
            showToast(getString(R.string.delete_updates_success_message), Toast.LENGTH_SHORT);
        } else {
            success = false;
            showToast(getString(mUpdateFolder.exists() ?
                    R.string.delete_updates_failure_message :
                    R.string.delete_updates_noFolder_message), Toast.LENGTH_SHORT);
        }
        return success;
    }

    @Override
    public void onStartUpdate(UpdatePreference pref) {
        final UpdateInfo updateInfo = pref.getUpdateInfo();

        // Prevent the dialog from being triggered more than once
        if (mStartUpdateVisible) {
            return;
        }

        if (!isStoragePermissionGranted(98456)) {
            showToast(getString(R.string.storage_permission_error), Toast.LENGTH_SHORT);
            return;
        }

        mStartUpdateVisible = true;
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setMessage(getString(R.string.checking_md5));
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setCancelable(false);
        mProgressDialog.show();

        new updateTask(this, updateInfo).execute();

    }

    private void showInstallDialog(UpdateInfo updateInfo) {
        mStartUpdateVisible = false;
        RebootHelper.showRebootDialog(this, Utils.makeUpdateFolder().getPath() + "/" + updateInfo.getFileName());
    }

    @Override
    public void onReady(UpdatePreference pref) {
        pref.setOnReadyListener(null);
        mUpdateHandler.post(mUpdateProgress);
    }

    public boolean isStoragePermissionGranted(int requestCode) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Constants.DOWNLOAD_REQUEST_CODE && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDownload();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return true;
    }

    private boolean isCheckingForUpdatesAllowed(){
        if (mCurrentUpdate == null){
            return true;
        }else if (mCurrentUpdate.getStyle() == UpdatePreference.STYLE_DOWNLOADING) {
            showToast(getString(R.string.error_download_in_progress), Toast.LENGTH_SHORT);
            return false;
        } else if (mCurrentUpdate.getStyle() == UpdatePreference.STYLE_DOWNLOADED || mCurrentUpdate.getStyle() == UpdatePreference.STYLE_COMPLETING) {
            showToast(getString(R.string.error_file_ready_to_install), Toast.LENGTH_SHORT);
            return false;
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_refresh:
                if (isCheckingForUpdatesAllowed()){ checkForUpdates(); }
                break;
            case R.id.menu_local_changelog:
                showLocalChangelog();
                break;
            case R.id.menu_delete_all:
                if (isCheckingForUpdatesAllowed()){ confirmDeleteAll(); }
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLocalChangelog(){
        startActivity(new Intent(this, LocalChangelogActivity.class));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Check if we need to refresh the screen to show new updates
        if (intent.getBooleanExtra(Constants.EXTRA_UPDATE_LIST_UPDATED, false)) {
            updateLayout(false);
        }

        checkForDownloadCompleted(intent);
    }

    private static class updateTask extends AsyncTask<Void, Void, Boolean> {
        private final WeakReference<UpdaterActivity> mActivityRef;
        private final UpdateInfo mUpdateInfo;
        private File updateFile;

        updateTask(UpdaterActivity activity, UpdateInfo updateInfo) {
            mUpdateInfo = updateInfo;
            mActivityRef = new WeakReference<>(activity);
        }

        @Override
        protected Boolean doInBackground(final Void... params) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            updateFile = new File(Utils.makeUpdateFolder().getPath() + "/" + mUpdateInfo.getFileName());
            return MD5.checkMD5(mUpdateInfo.getMD5(), updateFile);
        }

        @Override
        protected void onPostExecute(final Boolean result) {
            if (mActivityRef.get() != null) {
                if (mActivityRef.get().mProgressDialog != null) {
                    mActivityRef.get().mProgressDialog.dismiss();
                    mActivityRef.get().mProgressDialog = null;
                }
                if (result) {
                    if (Utils.isABDevice()) {
                       mActivityRef.get().showToast(mActivityRef.get().getString(R.string.update_manual_ab), Toast.LENGTH_LONG);
                       mActivityRef.get().mStartUpdateVisible = false;
                    } else {
                       mActivityRef.get().showInstallDialog(mUpdateInfo);
                    }
                } else {
                    try{
                        updateFile.delete();
                    }catch(Exception e){
                    }
                    mActivityRef.get().updateLayout(false);
                    new AlertDialog.Builder(mActivityRef.get())
                            .setMessage(mActivityRef.get().getString(R.string.md5_verification_failed))
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                }
                            })
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialog) {
                                    mActivityRef.get().mStartUpdateVisible = false;
                                }
                            })
                            .show();
                }
            }
        }
    }
}
