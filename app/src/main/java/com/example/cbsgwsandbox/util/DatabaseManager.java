package com.example.cbsgwsandbox.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.renderscript.Sampler;
import android.util.Log;

import com.couchbase.lite.BasicAuthenticator;
import com.couchbase.lite.CouchbaseLite;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.DatabaseChange;
import com.couchbase.lite.DatabaseChangeListener;
import com.couchbase.lite.DatabaseConfiguration;
import com.couchbase.lite.Document;
import com.couchbase.lite.Expression;
import com.couchbase.lite.IndexBuilder;
import com.couchbase.lite.ListenerToken;
import com.couchbase.lite.ReplicatedDocument;
import com.couchbase.lite.Replicator;
import com.couchbase.lite.ReplicatorChange;
import com.couchbase.lite.ReplicatorChangeListener;
import com.couchbase.lite.ReplicatorConfiguration;
import com.couchbase.lite.URLEndpoint;
import com.couchbase.lite.ValueIndexItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DatabaseManager {
    private static Database sandbox;

    private static DatabaseManager instance = null;

    public static String syncGatewayEndpoint = "ws://10.0.2.2:4984";

    private ListenerToken listenerToken;
    public String currentUser = null;

    public RingBuffer<String> replicationEvents;

    private static Replicator replicator;
    private static ListenerToken replicatorListenerToken;
    private boolean replication = false;

    protected DatabaseManager() {
        this.replicationEvents = new RingBuffer<>(1000);
    }

    public static DatabaseManager getSharedInstance() {
        if (instance == null) {

            instance = new DatabaseManager();
        }

        return instance;
    }

    public Database getDatabase() { return sandbox; }

    public void initializeCouchbaseLite(Context context)
    {
        CouchbaseLite.init(context);
    }

    public void openOrCreateDatabaseForUser(Context context, String username)
    {
        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setDirectory(String.format("%s/%s", context.getFilesDir(), username));

        currentUser = username;

        try {

            sandbox = new Database("sandbox", config);
            //registerForDatabaseChanges();
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }
    }

    /*public void openPrebuiltDatabase(Context context)
    {
        File dbFile = new File(context.getFilesDir(), "universities.cblite2");
        DatabaseConfiguration config = new DatabaseConfiguration();
        config.setDirectory(context.getFilesDir().toString());

        Log.i("CB-Update", "Will open Prebuilt DB  at path " + config.getDirectory());

        if (!dbFile.exists()) {
            AssetManager assetManager = context.getAssets();
            try {
                File path = new File(context.getFilesDir().toString());
                unzip(assetManager.open("universities.zip"), path);
                universityDatabase = new Database(universityDbName, config);
                createUniversityDatabaseIndexes();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (CouchbaseLiteException e) {
                e.printStackTrace();
            }
        }
        else {
            try {
                universityDatabase = new Database(universityDbName, config);
            } catch (CouchbaseLiteException e) {
                e.printStackTrace();
            }
        }
    }

    private void createUniversityDatabaseIndexes() {
        try {
            universityDatabase.createIndex("nameLocationIndex", IndexBuilder.valueIndex(ValueIndexItem.expression(Expression.property("name")),
                    ValueIndexItem.expression(Expression.property("location"))));
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }
    } */

    private void registerForDatabaseChanges()
    {
        // Add database change listener
        listenerToken = sandbox.addChangeListener(new DatabaseChangeListener() {
            @Override
            public void changed(final DatabaseChange change) {
                if (change != null) {
                    for(String docId : change.getDocumentIDs()) {
                        Document doc = sandbox.getDocument(docId);
                        if (doc != null) {
                            Log.i("DatabaseChangeEvent", "Document was added/updated");
                        }
                        else {

                            Log.i("DatabaseChangeEvent","Document was deleted");
                        }
                    }
                }
            }
        });
    }

    // tag::startPushAndPullReplicationForCurrentUser[]
    public void startPushAndPullReplicationForCurrentUser(String username, String password)
    // end::startPushAndPullReplicationForCurrentUser[]
    {
        URI url = null;
        try {
            url = new URI(String.format("%s/%s", syncGatewayEndpoint, "sandbox"));
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        // tag::replicationconfig[]
        ReplicatorConfiguration config = new ReplicatorConfiguration(sandbox, new URLEndpoint(url)); // <1>
        config.setReplicatorType(ReplicatorConfiguration.ReplicatorType.PUSH_AND_PULL); // <2>
        config.setContinuous(true); // <3>
        config.setAuthenticator(new BasicAuthenticator(username, password)); // <4>
        // config.setChannels(Arrays.asList(username)); // <5>
        // end::replicationconfig[]


        // tag::replicationinit[]
        replicator = new Replicator(config);
        // end::replicationinit[]

        // tag::replicationlistener[]
//        replicatorListenerToken = replicator.addChangeListener(new ReplicatorChangeListener() {
//            @Override
//            public void changed(ReplicatorChange change) {
//
//                if (change.getReplicator().getStatus().getActivityLevel().equals(Replicator.ActivityLevel.IDLE)) {
//                    Log.e("Replication Comp Log", "Scheduler Completed");
//                }
//                if (change.getReplicator().getStatus().getActivityLevel().equals(Replicator.ActivityLevel.STOPPED)
//                        || change.getReplicator().getStatus().getActivityLevel().equals(Replicator.ActivityLevel.OFFLINE)) {
//                    Log.e("Rep Scheduler  Log", "ReplicationTag Stopped");
//                }
//            }
//        });
        // end::replicationlistener[]

        replicatorListenerToken = replicator.addDocumentReplicationListener(replication -> {

            String repType = replication.isPush() ? "Push" : "Pull";
            for (ReplicatedDocument document : replication.getDocuments()) {

                CouchbaseLiteException err = document.getError();
                if (err != null) {
                    // There was an error
                    return;
                }

                String event = "%s - %s";
                this.replicationEvents.offer(event.format(event, repType, document.getID()));
            }
        });

        // tag::replicationstart[]
        replicator.start();
        this.replication = true;
        // end::replicationstart[]
    }

    // tag::stopAllReplicationForCurrentUser[]
    public void stopAllReplicationForCurrentUser()
    // end::stopAllReplicationForCurrentUser[]
    {
        // tag::replicationstop[]
        replicator.removeChangeListener(replicatorListenerToken);
        replicator.stop();

        this.replication = false;
        // end::replicationstop[]
    }

    public void resetCheckPoints() {
        replicator.resetCheckpoint();
    }

    public boolean isReplication() {
        return this.replication;
    }

    public void closeDatabaseForUser()
    {
        try {
            if (sandbox != null) {
                deregisterForDatabaseChanges();
                sandbox.close();
                sandbox = null;
            }
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }
    }

    /*public void closePrebuiltDatabase()
    {
        try {
            if (userprofileDatabase != null) {
                deregisterForDatabaseChanges();
                userprofileDatabase.close();
                userprofileDatabase = null;
            }
        } catch (CouchbaseLiteException e) {
            e.printStackTrace();
        }
    } */

    private void deregisterForDatabaseChanges()
    {
        if (listenerToken != null) {
            sandbox.removeChangeListener(listenerToken);
        }
    }

    private static void unzip(InputStream in, File destination) throws IOException {
        byte[] buffer = new byte[1024];

        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry ze = zis.getNextEntry();

        while (ze != null) {
            String fileName = ze.getName();
            File newFile = new File(destination, fileName);

            if (ze.isDirectory()) {
                newFile.mkdirs();
            } else {
                new File(newFile.getParent()).mkdirs();

                FileOutputStream fos = new FileOutputStream(newFile);

                int len;

                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }

                fos.close();
            }

            ze = zis.getNextEntry();
        }

        zis.closeEntry();
        zis.close();

        in.close();
    }
}
