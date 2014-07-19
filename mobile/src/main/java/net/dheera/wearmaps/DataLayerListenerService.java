package net.dheera.wearmaps;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Random;
import java.util.Scanner;

/**
 * Created by Dheera Venkatraman
 * http://dheera.net
 */
public class DataLayerListenerService extends WearableListenerService {

    private static final String TAG = "WearMaps/" + String.valueOf((new Random()).nextInt(10000));
    private static final boolean D = false;

    GoogleApiClient mGoogleApiClient;
    LocationManager mLocationManager;
    WearMapsLocationListener mWearMapsLocationListener;
    private Node mWearableNode = null;

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

    void findWearableNode() {
        PendingResult<NodeApi.GetConnectedNodesResult> nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient);
        nodes.setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(NodeApi.GetConnectedNodesResult result) {
                if(result.getNodes().size()>0) {
                    mWearableNode = result.getNodes().get(0);
                    if(D) Log.d(TAG, "Found wearable: name=" + mWearableNode.getDisplayName() + ", id=" + mWearableNode.getId());
                    try {Thread.sleep(5000);}catch(InterruptedException e){}
                } else {
                    mWearableNode = null;
                }
            }
        });
    }

    private void onMessageStart() {
        Log.d(TAG, "onMessageStart");
        if(mLocationManager != null && mWearMapsLocationListener != null) {
            Criteria myCriteria = new Criteria();
            myCriteria.setAccuracy(Criteria.ACCURACY_FINE);
            String myProvider = mLocationManager.getBestProvider(myCriteria, true);
            mLocationManager.requestLocationUpdates(myProvider, 5000, 0, mWearMapsLocationListener);
        }

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

    private void onMessageStop() {
        Log.d(TAG, "onMessageStop");
        // if(Looper.myLooper() != null) { Looper.myLooper().quit(); }
        if(mLocationManager != null && mWearMapsLocationListener != null) {
             mLocationManager.removeUpdates(mWearMapsLocationListener);
        }
        stopForeground(true);
    }

    private void onMessageLocate() {
        Log.d(TAG, "onMessageLocate");
        if(mLocationManager != null) {
            Location location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if(location == null) {
                location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if(location != null) {
                sendToWearable(String.format("location %f %f", location.getLatitude(), location.getLongitude()), null, null);
            }
        }
    }

    private void onMessageGet(int y, int x, double latitude, double longitude, int googleZoom) {
        if(D) Log.d(TAG, String.format("onMessageGet(%f, %f, %d)", latitude, longitude, googleZoom));

        final String maptype = "roadmap";
        final String format = "jpg";

        InputStream is;
        String url = String.format(
                "http://maps.googleapis.com/maps/api/staticmap?center=%f,%f&zoom=%d&size=512x562&maptype=%s&format=%s",
                latitude, longitude, googleZoom, maptype, format);

        if(D) Log.d(TAG, "onMessageGet: url: " + url);

        try {
            // download the image and send it to the wearable
            byte[] outdata = downloadUrl(new URL(url));
            if(D) Log.d(TAG, String.format("read %d bytes", outdata.length));
            sendToWearable(String.format("response %d %d %f %f %d", y, x, latitude, longitude, googleZoom), outdata, null);
        } catch (Exception e) {
            Log.e(TAG, "onMessageGet: exception:", e);
        }
    }

    private byte[] downloadUrl(URL toDownload) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            byte[] chunk = new byte[4096];
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


        Log.d(TAG, "init");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        if(D) Log.d(TAG, "onConnected: " + connectionHint);
                        findWearableNode();
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        if(D) Log.d(TAG, "onConnectionSuspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        if(D) Log.d(TAG, "onConnectionFailed: " + result);
                    }
                })
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();

        mWearMapsLocationListener = new WearMapsLocationListener();
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if(Looper.myLooper() == null) { Looper.prepare();Looper.loop(); }

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand");
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        if(D) Log.d(TAG, "onDestroy");
        mLocationManager.removeUpdates(mWearMapsLocationListener);
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
        if(D) Log.d(TAG, "onMessageReceived: " + m.getPath());
        if(D) Log.d(TAG, "onMessageReceived");
        if(D) Log.d(TAG, "path: " + m.getPath());
        if(D) Log.d(TAG, "data bytes: " + m.getData().length);

        Scanner scanner = new Scanner(m.getPath());
        String requestType = scanner.next();

        if(D) Log.d(TAG, "requestType: " + requestType);

        if(requestType.equals("get")) {
            int y = scanner.nextInt();
            int x = scanner.nextInt();
            double latitude = scanner.nextDouble();
            double longitude = scanner.nextDouble();
            int googleZoom = scanner.nextInt();
            onMessageGet(y, x, latitude, longitude, googleZoom);
        } if(requestType.equals("locate")) {
            onMessageLocate();
        } if(requestType.equals("start")) {
            onMessageStart();
        } if(requestType.equals("stop")) {
            onMessageStop();
        }
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        // i don't care
    }

    private class WearMapsLocationListener implements LocationListener {
        public final boolean D = true;
        private final String TAG = "WearMapsLocationListener";
        @Override
        public void onLocationChanged(Location location) {
            if(D) Log.d(TAG, String.format("received location: %f %f %f", location.getLatitude(), location.getLongitude(), location.getAccuracy()));
            sendToWearable(String.format("location %f %f", location.getLatitude(), location.getLongitude()), null, null);
        }
        @Override
        public void onProviderDisabled(String provider) {
            if(D) Log.d(TAG, "provider disabled: " + provider);
        }
        @Override
        public void onProviderEnabled(String provider) {
            if(D) Log.d(TAG, "provider enabled: " + provider);
        }
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            if(D) Log.d(TAG, "status changed to " + String.valueOf(status));
        }
    }

}

