package net.dheera.wearmaps;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationRequest;

/**
 * Created by dheera on 7/18/14.
 */
public class FusedLocationListener implements GooglePlayServicesClient.ConnectionCallbacks, GooglePlayServicesClient.OnConnectionFailedListener, com.google.android.gms.location.LocationListener  {

    public interface LocationListener {
        public void onReceiveLocation(Location location);
    }

    private LocationListener mListener;

    public static final String TAG = "Fused";
    private LocationClient locationClient;
    private LocationRequest locationRequest;

    private Location lastLocation = null;

    protected int minDistanceToUpdate = 1000;
    protected int minTimeToUpdate = 10*1000;

    protected Context mContext;

    public Location getLastLocation() {
        return lastLocation;
    }

    @Override
    public void onConnected(Bundle bundle) {
        Log.d(TAG, "Connected");
        locationRequest = new LocationRequest();
        locationRequest.setSmallestDisplacement(0);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(5000);
        locationRequest.setNumUpdates(120);
        locationClient.requestLocationUpdates(locationRequest, this);
        lastLocation = locationClient.getLastLocation();
        if(lastLocation == null) Log.d(TAG, "last location is null"); else Log.d(TAG, "last location is not null");
        mListener.onReceiveLocation(lastLocation);
    }

    @Override
    public void onDisconnected() {
        Log.d(TAG, "Disconnected");
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.d(TAG, "Failed");
    }


    private static FusedLocationListener instance;

    public static synchronized FusedLocationListener getInstance(Context context, LocationListener listener){
        if (null==instance) {
            instance = new FusedLocationListener(context, listener);
        }
        return instance;
    }


    private FusedLocationListener(Context context, LocationListener listener){
        mContext = context;
        mListener = listener;
    }


    public void start(){

        Log.d(TAG, "Listener started");
        locationClient = new LocationClient(mContext,this,this);
        locationClient.connect();

    }


    @Override
    public void onLocationChanged(Location location) {
        Log.d(TAG, "Location received: " + location.getLatitude() + ";" + location.getLongitude());
        //notify listener with new location
        mListener.onReceiveLocation(location);
        lastLocation = location;
    }


    public void stop() {
        if(locationClient != null)
            locationClient.removeLocationUpdates(this);
    }
}