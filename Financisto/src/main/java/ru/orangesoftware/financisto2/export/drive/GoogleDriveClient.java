/*
 * Copyright (c) 2011 Denis Solonenko.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 */

package ru.orangesoftware.financisto2.export.drive;

import android.content.Context;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.drive.Contents;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.drive.DriveApi;
import com.google.android.gms.drive.DriveContents;
import com.google.android.gms.drive.DriveFile;
import com.google.android.gms.drive.DriveFolder;
import com.google.android.gms.drive.DriveId;
import com.google.android.gms.drive.Metadata;
import com.google.android.gms.drive.MetadataBuffer;
import com.google.android.gms.drive.MetadataChangeSet;
import com.google.android.gms.drive.query.Filters;
import com.google.android.gms.drive.query.Query;
import com.google.android.gms.drive.query.SearchableField;

import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EBean;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import ru.orangesoftware.financisto2.R;
import ru.orangesoftware.financisto2.backup.DatabaseExport;
import ru.orangesoftware.financisto2.backup.DatabaseImport;
import ru.orangesoftware.financisto2.bus.GreenRobotBus;
import ru.orangesoftware.financisto2.db.CategoryRepository;
import ru.orangesoftware.financisto2.db.DatabaseAdapter;
import ru.orangesoftware.financisto2.export.Export;
import ru.orangesoftware.financisto2.export.ImportExportException;
import ru.orangesoftware.financisto2.utils.MyPreferences;

import static java.util.Arrays.asList;

/**
 * Created by IntelliJ IDEA.
 * User: Denis Solonenko
 * Date: 11/9/11 2:19 AM
 */
@EBean(scope = EBean.Scope.Singleton)
public class GoogleDriveClient {

    private final Context context;

    @Bean
    GreenRobotBus bus;

    @Bean
    DatabaseAdapter db;

    @Bean
    CategoryRepository categoryRepository;

    private GoogleApiClient googleApiClient;

    public GoogleDriveClient(Context context) {
        this.context = context.getApplicationContext();
    }

    @AfterInject
    public void init() {
        bus.register(this);
    }

    public ConnectionResult connect() throws ImportExportException {
        if (googleApiClient == null) {
            String googleDriveAccount = MyPreferences.getGoogleDriveAccount(context);
            if (googleDriveAccount == null) {
                throw new ImportExportException(R.string.google_drive_account_required);
            }
            googleApiClient = new GoogleApiClient.Builder(context)
                    .addApi(Drive.API)
                    .addScope(Drive.SCOPE_FILE)
                    .setAccountName(googleDriveAccount)
                    .build();
        }
        return googleApiClient.blockingConnect(1, TimeUnit.MINUTES);
    }

    public void disconnect() {
        if (googleApiClient != null) {
            googleApiClient.disconnect();
        }
    }

    public void onEventBackgroundThread(DoBackup event) {
        DatabaseExport export = new DatabaseExport(context, db.db(), true);
        try {
            String targetFolder = getDriveFolderName();
            ConnectionResult connectionResult = connect();
            if (connectionResult.isSuccess()) {
                DriveFolder folder = getDriveFolder(targetFolder);
                String fileName = export.generateFilename();
                byte[] bytes = export.generateBackupBytes();
                Status status = createFile(folder, fileName, bytes);
                if (status.isSuccess()) {
                    handleSuccess(fileName);
                } else {
                    handleFailure(status);
                }
            } else {
                handleConnectionResult(connectionResult);
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    public void onEventBackgroundThread(DoListFiles event) {
        try {
            String targetFolder = getDriveFolderName();
            ConnectionResult connectionResult = connect();
            if (connectionResult.isSuccess()) {
                DriveFolder folder = getDriveFolder(targetFolder);
                Query query = new Query.Builder()
                        .addFilter(Filters.and(
                                Filters.eq(SearchableField.MIME_TYPE, Export.BACKUP_MIME_TYPE),
                                Filters.eq(SearchableField.TRASHED, false)
                        ))
                        .build();
                DriveApi.MetadataBufferResult metadataBufferResult = folder.queryChildren(googleApiClient, query).await();
                if (metadataBufferResult.getStatus().isSuccess()) {
                    List<DriveFileInfo> driveFiles = fetchFiles(metadataBufferResult);
                    handleSuccess(driveFiles);
                } else {
                    handleFailure(metadataBufferResult.getStatus());
                }
            } else {
                handleConnectionResult(connectionResult);
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    public void onEventBackgroundThread(DoRestore event) {
        try {
            String targetFolder = getDriveFolderName();
            ConnectionResult connectionResult = connect();
            if (connectionResult.isSuccess()) {
                DriveFolder folder = getDriveFolder(targetFolder);
                DriveFile file = Drive.DriveApi.getFile(googleApiClient, event.selectedDriveFile.driveId);
                DriveApi.DriveContentsResult contentsResult = file.open(googleApiClient, DriveFile.MODE_READ_ONLY, null).await();
                if (contentsResult.getStatus().isSuccess()) {
                    DriveContents contents = contentsResult.getDriveContents();
                    try {
                        DatabaseImport.createFromGoogleDriveBackup(context, db, categoryRepository, contents).importDatabase();
                        bus.post(new DriveRestoreSuccess());
                    } finally {
                        contents.discard(googleApiClient);
                    }
                } else {
                    handleFailure(contentsResult.getStatus());
                }
            } else {
                handleConnectionResult(connectionResult);
            }
        } catch (Exception e) {
            handleError(e);
        }
    }

    protected List<DriveFileInfo> fetchFiles(DriveApi.MetadataBufferResult metadataBufferResult) {
        List<DriveFileInfo> files = new ArrayList<DriveFileInfo>();
        MetadataBuffer metadataBuffer = metadataBufferResult.getMetadataBuffer();
        if (metadataBuffer == null) return files;
        try {
            for (Metadata metadata : metadataBuffer) {
                if (metadata == null) continue;
                String title = metadata.getTitle();
                if (!title.endsWith(".backup")) continue;
                files.add(new DriveFileInfo(metadata.getDriveId(), title, metadata.getCreatedDate()));
            }
        } finally {
            metadataBuffer.close();
        }
        Collections.sort(files);
        return files;
    }

    protected String getDriveFolderName() throws ImportExportException {
        String folder = MyPreferences.getBackupFolder(context);
        // check the backup folder registered on preferences
        if (folder == null || folder.equals("")) {
            throw new ImportExportException(R.string.gdocs_folder_not_configured);
        }
        return folder;
    }

    protected DriveFolder getDriveFolder(String targetFolder) throws IOException, ImportExportException {
        DriveFolder folder = getOrCreateDriveFolder(targetFolder);
        if (folder == null) {
            throw new ImportExportException(R.string.gdocs_folder_not_found);
        }
        return folder;
    }

    public DriveFolder getOrCreateDriveFolder(String targetFolder) throws IOException {
        Query query = new Query.Builder().addFilter(Filters.and(
                Filters.eq(SearchableField.TRASHED, false),
                Filters.eq(SearchableField.TITLE, targetFolder),
                Filters.eq(SearchableField.MIME_TYPE, "application/vnd.google-apps.folder")
        )).build();
        DriveApi.MetadataBufferResult result = Drive.DriveApi.query(googleApiClient, query).await();
        if (result.getStatus().isSuccess()) {
            DriveId driveId = fetchDriveId(result);
            if (driveId != null) {
                return Drive.DriveApi.getFolder(googleApiClient, driveId);
            }
        }
        return createDriveFolder(targetFolder);
    }

    private DriveFolder createDriveFolder(String targetFolder) {
        MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                .setTitle(targetFolder).build();
        DriveFolder.DriveFolderResult result = Drive.DriveApi.getRootFolder(googleApiClient).createFolder(googleApiClient, changeSet).await();
        if (result.getStatus().isSuccess()) {
            return result.getDriveFolder();
        } else {
            return null;
        }
    }

    private DriveId fetchDriveId(DriveApi.MetadataBufferResult result) {
        MetadataBuffer buffer = result.getMetadataBuffer();
        try {
            for (Metadata metadata : buffer) {
                if (metadata == null) continue;
                return metadata.getDriveId();
            }
        } finally {
            buffer.close();
        }
        return null;
    }

    public Status createFile(DriveFolder folder, String fileName, byte[] bytes) throws IOException {
        MetadataChangeSet changeSet = new MetadataChangeSet.Builder()
                .setTitle(fileName)
                .setMimeType(Export.BACKUP_MIME_TYPE).build();
        // Create a file in the root folder
        DriveApi.DriveContentsResult contentsResult = Drive.DriveApi.newDriveContents(googleApiClient).await();
        Status contentsResultStatus = contentsResult.getStatus();
        if (contentsResultStatus.isSuccess()) {
            DriveContents contents = contentsResult.getDriveContents();
            contents.getOutputStream().write(bytes);
            DriveFolder.DriveFileResult fileResult = folder.createFile(googleApiClient, changeSet, contents).await();
            return fileResult.getStatus();
        } else {
            return contentsResultStatus;
        }
    }

    private void handleConnectionResult(ConnectionResult connectionResult) {
        bus.post(new DriveConnectionFailed(connectionResult));
    }

    private void handleError(Exception e) {
        bus.post(new DriveBackupError(e));
    }

    private void handleFailure(Status status) {
        bus.post(new DriveBackupFailure(status));
    }

    private void handleSuccess(String fileName) {
        bus.post(new DriveBackupSuccess(fileName));
    }

    private void handleSuccess(List<DriveFileInfo> files) {
        bus.post(new DriveFileList(files));
    }

}
