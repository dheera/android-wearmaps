package net.dheera.wearmaps;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * Created by Dheera Venkatraman
 * http://dheera.net
 */
public class DataLayerListenerService extends WearableListenerService implements GooglePlayServicesClient.ConnectionCallbacks, GooglePlayServicesClient.OnConnectionFailedListener, LocationListener {

    private static final String TAG = "WearMaps/" + String.valueOf((new Random()).nextInt(10000));
    private static final boolean D = true;

    private GoogleApiClient mGoogleApiClient = null;
    private LocationClient mLocationClient = null;
    private LocationRequest mLocationRequest = null;
    private Node mWearableNode = null;

    private long lastPingTime = 0;

    private void sendToWearable(String path, byte[] data, final ResultCallback<MessageApi.SendMessageResult> callback) {
        if (mWearableNode != null) {
            PendingResult<MessageApi.SendMessageResult> pending = Wearable.MessageApi.sendMessage(mGoogleApiClient, mWearableNode.getId(), path, data);
            pending.setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                @Override
                public void onResult(MessageApi.SendMessageResult result) {
                    if (callback != null) {
                        callback.onResult(result);
                    }
                    if (!result.getStatus().isSuccess()) {
                        if(D) Log.d(TAG, "ERROR: failed to send Message: " + result.getStatus());
                    }
                }
            });
        } else {
            if(D) Log.d(TAG, "ERROR: tried to send message before device was found");
        }
    }

    void findWearableNodeAndBlock() {
        PendingResult<NodeApi.GetConnectedNodesResult> nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient);
        nodes.setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(NodeApi.GetConnectedNodesResult result) {
                if(result.getNodes().size()>0) {
                    mWearableNode = result.getNodes().get(0);
                    if(D) Log.d(TAG, "Found wearable: name=" + mWearableNode.getDisplayName() + ", id=" + mWearableNode.getId());
                } else {
                    mWearableNode = null;
                }
            }
        });
        int i = 0;
        while(mWearableNode == null && i++<50) {
            try {
                Thread.sleep(100);
            } catch(InterruptedException e ) {
                // don't care
            }
        }
    }

    private void onMessageStart() {
        Log.d(TAG, "onMessageStart");

        Notification note=new Notification(R.drawable.ic_launcher,
                "Wear Maps is active",
                System.currentTimeMillis());
        Intent i=new Intent(this, MainActivity.class);

        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pi=PendingIntent.getActivity(this, 0,
                i, 0);

        note.setLatestEventInfo(this, "Wear Maps",
                "Wear Maps is active",
                pi);
        note.flags|=Notification.FLAG_NO_CLEAR;

        startForeground(1337, note);
    }

    private void onMessagePing() {
        lastPingTime = System.currentTimeMillis();
    }

    private void onMessageStop() {
        Log.d(TAG, "onMessageStop");
        if(mLocationClient != null && mLocationClient.isConnected()) {
            mLocationClient.disconnect();
        }
        stopForeground(true);
    }

    private void onMessageLocate() {
        Log.d(TAG, "onMessageLocate");

        if (mLocationClient != null && mLocationClient.isConnected()) {
            Location location = mLocationClient.getLastLocation();
            if (location == null) {
                if (D) Log.d(TAG, "No location available");
            } else {
                if (D)
                    Log.d(TAG, String.format("Got location: %f %f %f", location.getLatitude(), location.getLongitude(), location.getAccuracy()));
                sendToWearable(String.format("location %f %f", location.getLatitude(), location.getLongitude()), null, null);
            }
        }
    }

    private void onMessageGet(final int y, final int x, final double latitude, final double longitude, final int googleZoom) {
        if(D) Log.d(TAG, String.format("onMessageGet(%f, %f, %d)", latitude, longitude, googleZoom));

        final String maptype = "roadmap";
        final String format = "jpg";

        InputStream is;
        final String url = String.format(
                "http://maps.googleapis.com/maps/api/staticmap?center=%f,%f&zoom=%d&size=256x282&maptype=%s&format=%s",
                latitude, longitude, googleZoom, maptype, format);

        if(D) Log.d(TAG, "onMessageGet: url: " + url);

        new Thread(new Runnable() {
            public void run() {
                try {
                    // download the image and send it to the wearable
                    try {
                        byte[] outdata = downloadUrl2(url);
                        if(D) Log.d(TAG, String.format("read %d bytes", outdata.length));
                        sendToWearable(String.format("response %d %d %f %f %d", y, x, latitude, longitude, googleZoom), outdata, null);
                    } catch(Exception e) {
                        e.printStackTrace();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "onMessageGet: exception:", e);
                }
            }
        }).start();


    }

    private byte[] downloadUrl2(String url) {
        HttpGet httpGet = new HttpGet(url);
        HttpClient httpclient = new DefaultHttpClient();
        byte[] content;
        try {
            HttpResponse response = httpclient.execute(httpGet);
            return EntityUtils.toByteArray(response.getEntity());
        } catch(Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] downloadUrl(URL toDownload) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            byte[] chunk = new byte[16384];
            int bytesRead;
            InputStream stream = toDownload.openStream();

            while ((bytesRead = stream.read(chunk)) > 0) {
                outputStream.write(chunk, 0, bytesRead);
            }

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return outputStream.toByteArray();
    }

    @Override
    public void onCreate() {
        if(D) Log.d(TAG, "onCreate");
        super.onCreate();

        try {
            Class.forName("android.os.AsyncTask");
        } catch(ClassNotFoundException e) {
            e.printStackTrace();
        }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        if(D) Log.d(TAG, "onDestroy");
        if(mLocationClient != null && mLocationClient.isConnected()) {
            mLocationClient.removeLocationUpdates(this);
        }
        super.onDestroy();
    }

    @Override
    public void onPeerConnected(Node peer) {
        if(D) Log.d(TAG, "onPeerConnected");
        super.onPeerConnected(peer);
        if(D) Log.d(TAG, "Connected: name=" + peer.getDisplayName() + ", id=" + peer.getId());
    }

    @Override
    public void onMessageReceived(MessageEvent m) {
        if(D) Log.d(TAG, "onMessageReceived");
        if(D) Log.d(TAG, "path: " + m.getPath());
        if(D) Log.d(TAG, "data bytes: " + m.getData().length);

        Scanner scanner = new Scanner(m.getPath());
        String requestType = scanner.next();

        if (D) Log.d(TAG, "requestType: " + requestType);

        if (requestType.equals("stop")) {
            onMessageStop();
            return;
        }

        if(mGoogleApiClient == null) {
            if(D) Log.d(TAG, "setting up GoogleApiClient");
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Wearable.API)
                    .addApi(LocationServices.API)
                    .build();
            if(D) Log.d(TAG, "connecting to GoogleApiClient");
            ConnectionResult connectionResult = mGoogleApiClient.blockingConnect(30, TimeUnit.SECONDS);

            if (!connectionResult.isSuccess()) {
                Log.e(TAG, String.format("GoogleApiClient connect failed with error code %d", connectionResult.getErrorCode()));
                return;
            } else {
                if(D) Log.d(TAG, "GoogleApiClient connect success, finding wearable node");
                findWearableNodeAndBlock();
                if(D) Log.d(TAG, "wearable node found");
            }
        } else if(mWearableNode == null) {
            if(D) Log.d(TAG, "GoogleApiClient was connceted but wearable not found, finding wearable node");
            findWearableNodeAndBlock();
            if(mWearableNode == null) {
                if(D) Log.d(TAG, "wearable node not found");
                return;
            }
        }

        if(mLocationClient == null) {
            if (mLocationClient == null) mLocationClient = new LocationClient(this, this, this);
        }

        if(!mLocationClient.isConnected()) {
            mLocationClient.connect();
            while(mLocationClient.isConnecting()) {
                try {
                    Thread.sleep(50);
                } catch(InterruptedException e) { e.printStackTrace(); }
            }
        }

        if (requestType.equals("get")) {
            if (!scanner.hasNextInt()) {
                if (D) Log.d(TAG, "invalid message parameter");
                return;
            }
            int y = scanner.nextInt();
            if (!scanner.hasNextInt()) {
                if (D) Log.d(TAG, "invalid message parameter");
                return;
            }
            int x = scanner.nextInt();
            if (!scanner.hasNextDouble()) {
                if (D) Log.d(TAG, "invalid message parameter");
                return;
            }
            double latitude = scanner.nextDouble();
            if (!scanner.hasNextDouble()) {
                if (D) Log.d(TAG, "invalid message parameter");
                return;
            }
            double longitude = scanner.nextDouble();
            if (!scanner.hasNextInt()) {
                if (D) Log.d(TAG, "invalid message parameter");
                return;
            }
            int googleZoom = scanner.nextInt();
            onMessageGet(y, x, latitude, longitude, googleZoom);
        }
        if (requestType.equals("locate")) {
            onMessageLocate();
        }
        if (requestType.equals("start")) {
            onMessageStart();
        }
        if (requestType.equals("ping")) {
            onMessagePing();
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        // don't care
    }

    @Override
    public void onLocationChanged(Location location) {
        if(D) Log.d(TAG, String.format("received location: %f %f %f", location.getLatitude(), location.getLongitude(), location.getAccuracy()));
        sendToWearable(String.format("location %f %f", location.getLatitude(), location.getLongitude()), null, null);

        if (System.currentTimeMillis() - lastPingTime > 15000) {
            Log.d(TAG, String.format("ping timeout %d ms, disconnecting", System.currentTimeMillis() - lastPingTime));
            mLocationClient.removeLocationUpdates(this);
            mLocationClient.disconnect();
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        mLocationRequest = LocationRequest.create();
        mLocationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
        mLocationRequest.setInterval(5000);
        mLocationRequest.setFastestInterval(5000);
        mLocationClient.requestLocationUpdates(mLocationRequest, this);
    }
    @Override
    public void onDisconnected() {
        mLocationClient = null;
    }
    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        if(D) Log.d(TAG, "connection failed");
        if (connectionResult.hasResolution()) {
            if(D) Log.d(TAG, "has resolution");
        } else {
            if(D) Log.d(TAG, "no resolution");
        }
    }
}

